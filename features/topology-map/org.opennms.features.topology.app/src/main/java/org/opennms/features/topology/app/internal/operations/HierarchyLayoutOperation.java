/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2015-2015 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2015 The OpenNMS Group, Inc.
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

package org.opennms.features.topology.app.internal.operations;

import org.opennms.features.topology.api.GraphContainer;
import org.opennms.features.topology.api.LayoutAlgorithm;
import org.opennms.features.topology.app.internal.jung.HierarchyLayoutAlgorithm;

public class HierarchyLayoutOperation extends LayoutOperation {

    public HierarchyLayoutOperation() {
        super(new LayoutFactory() {

            @Override
            public LayoutAlgorithm getLayoutAlgorithm() {
                return new HierarchyLayoutAlgorithm();
            }
        });
    }


    @Override
    public String getId() {
        return getClass().getSimpleName();
    }

    // Prevent non hierarchical topologies to use this operation. See NMS-8703
    @Override
    protected boolean enabled(GraphContainer container) {
        return container.getBaseTopology().getTopologyProviderInfo().isHierarchical();
    }
}
