/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2016 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2016 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/
package org.opennms.smoketest.minion;

import static com.jayway.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.opennms.core.criteria.CriteriaBuilder;
import org.opennms.netmgt.dao.hibernate.MinionDaoHibernate;
import org.opennms.netmgt.model.minion.OnmsMinion;
import org.opennms.smoketest.utils.DaoUtils;
import org.opennms.test.system.api.AbstractTestEnvironment;
import org.opennms.test.system.api.NewTestEnvironment.ContainerAlias;
import org.opennms.test.system.api.TestEnvironmentBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.RateLimiter;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.ContainerInfo;

/**
 * This test will send syslog messages over the following message bus:
 * 
 * Minion -> Kafka -> OpenNMS Eventd -> Elasticsearch REST -> Elasticsearch 5
 * 
 * and will restart the Elasticsearch system several times to ensure that
 * no messages are lost.
 * 
 * @author Seth
 */
public class SyslogKafkaElasticsearch5OutageIT extends AbstractSyslogTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(SyslogKafkaElasticsearch5OutageIT.class);

    @Override
    protected TestEnvironmentBuilder getEnvironmentBuilder() {
        TestEnvironmentBuilder builder = super.getEnvironmentBuilder();
        // Enable Elasticsearch 5
        return builder.es5();
    }

    @Test
    public void testMinionSyslogsOverKafkaToEsRest() throws Exception {
        Date startOfTest = new Date();
        int numMessages = 10000;
        int packetsPerSecond = 250;

        InetSocketAddress minionSshAddr = testEnvironment.getServiceAddress(ContainerAlias.MINION, 8201);
        InetSocketAddress opennmsSshAddr = testEnvironment.getServiceAddress(ContainerAlias.OPENNMS, 8101);
        InetSocketAddress kafkaAddress = testEnvironment.getServiceAddress(ContainerAlias.KAFKA, 9092);
        InetSocketAddress zookeeperAddress = testEnvironment.getServiceAddress(ContainerAlias.KAFKA, 2181);

        // Install the Kafka syslog and trap handlers on the Minion system
        installFeaturesOnMinion(minionSshAddr, kafkaAddress);

        // Install the Kafka and Elasticsearch features on the OpenNMS system
        installFeaturesOnOpenNMS(opennmsSshAddr, kafkaAddress, zookeeperAddress);

        final String sender = testEnvironment.getContainerInfo(ContainerAlias.SNMPD).networkSettings().ipAddress();

        // Wait for the minion to show up
        await().atMost(90, SECONDS).pollInterval(5, SECONDS)
            .until(DaoUtils.countMatchingCallable(
                 getDaoFactory().getDao(MinionDaoHibernate.class),
                 new CriteriaBuilder(OnmsMinion.class)
                     .gt("lastUpdated", startOfTest)
                     .eq("location", "MINION")
                     .toCriteria()
                 ),
                 is(1)
             );

        LOG.info("Warming up syslog routes by sending 100 packets");

        // Warm up the routes
        sendMessage(ContainerAlias.MINION, sender, 100);

        for (int i = 0; i < 10; i++) {
            LOG.info("Slept for " + i + " seconds");
            Thread.sleep(1000);
        }

        LOG.info("Resetting statistics");
        resetRouteStatistics(opennmsSshAddr, minionSshAddr);

        for (int i = 0; i < 20; i++) {
            LOG.info("Slept for " + i + " seconds");
            Thread.sleep(1000);
        }

        // Make sure that this evenly divides into the numMessages
        final int chunk = 250;
        // Make sure that this is an even multiple of chunk
        final int logEvery = 1000;

        int count = 0;
        long start = System.currentTimeMillis();

        AtomicInteger restartCounter = new AtomicInteger();

        // Start a timer that occasionally restarts Elasticsearch
        Timer restarter = new Timer("Elasticsearch-Restarter", true);
        restarter.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                final DockerClient docker = ((AbstractTestEnvironment)testEnvironment).getDockerClient();
                final String id = testEnvironment.getContainerInfo(ContainerAlias.ELASTICSEARCH_5).id();
                try {
                    LOG.info("Restarting container: {}", id);
                    docker.restartContainer(id);
                    restartCounter.incrementAndGet();
                    LOG.info("Container restarted: {}", id);
                } catch (DockerException | InterruptedException e) {
                    LOG.warn("Unexpected exception while restarting container {}", id, e);
                }
            }
        }, 0L, TimeUnit.SECONDS.toMillis(29));

        // Send ${numMessages} syslog messages
        RateLimiter limiter = RateLimiter.create(packetsPerSecond);
        for (int i = 0; i < (numMessages / chunk); i++) {
            limiter.acquire(chunk);
            sendMessage(ContainerAlias.MINION, sender, chunk);
            count += chunk;
            if (count % logEvery == 0) {
                long mid = System.currentTimeMillis();
                LOG.info(String.format("Sent %d packets in %d milliseconds", logEvery, mid - start));
                start = System.currentTimeMillis();
            }
        }

        // Stop restarting Elasticsearch
        restarter.cancel();

        // 100 warm-up messages plus ${numMessages} messages
        pollForElasticsearchEventsUsingJest(this::getEs5Address, 100 + numMessages);

        assertTrue("Elasticsearch was never restarted", restartCounter.get() > 0);
    }

    protected InetSocketAddress getEs5Address() {
        try {
            // Fetch an up-to-date ContainerInfo for the ELASTICSEARCH_5 container
            final DockerClient docker = ((AbstractTestEnvironment)testEnvironment).getDockerClient();
            final String id = testEnvironment.getContainerInfo(ContainerAlias.ELASTICSEARCH_5).id();
            ContainerInfo info = docker.inspectContainer(id);
            return testEnvironment.getServiceAddress(info, 9200, "tcp"); 
        } catch (DockerException | InterruptedException e) {
            LOG.error("Unexpected exception trying to fetch Elassticsearch port", e);
            return null;
        }
    }
}
