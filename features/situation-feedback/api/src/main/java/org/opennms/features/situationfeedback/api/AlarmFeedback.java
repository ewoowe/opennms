/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2018-2018 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2018 The OpenNMS Group, Inc.
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

package org.opennms.features.situationfeedback.api;

import java.util.Objects;

import com.google.common.base.Enums;

/**
 * Expresses Feedback on the Correlation of an Alarm.
 */
public class AlarmFeedback {

    public enum FeedbackType {
        FALSE_POSITIVE, // Alarm does not belong in this Situation
        FALSE_NEGATIVE, // Alarm was missing from this Situation
        CORRECT, // Alarm is correctly correlated
        UNKNOWN;

        public static FeedbackType valueOfOrUnknown(String type) {
            return Enums.getIfPresent(FeedbackType.class, type).or(UNKNOWN);
        }
    }

    // Situation ReductionKey
    private final String situationKey;

    // this may not be nesc but may prove helpful
    private final String situationFingerprint; // fingerprint of situation/alarms at time of feedback

    // Alarm ReductionKey
    private final String alarmKey;

    private final FeedbackType feedbackType;

    private final String reason;

    private final String user;

    private final long timestamp;

    private AlarmFeedback(Builder builder) {
        this.situationKey = builder.situationKey;
        this.situationFingerprint = builder.situationFingerprint;
        this.alarmKey = builder.alarmKey;
        this.feedbackType = builder.feedbackType;
        this.reason = builder.reason;
        this.user = builder.user;
        this.timestamp = builder.timestamp;
    }

    public static class Builder {
        private String situationKey;
        private String situationFingerprint;
        private String alarmKey;
        private AlarmFeedback.FeedbackType feedbackType;
        private String reason;
        private String user;
        private long timestamp;

        private Builder() {
            timestamp = System.currentTimeMillis();
        }

        public Builder withSituationKey(String situationKey) {
            this.situationKey = situationKey;
            return this;
        }

        public Builder withSituationFingerprint(String situationFingerprint) {
            this.situationFingerprint = situationFingerprint;
            return this;
        }

        public Builder withAlarmKey(String alarmKey) {
            this.alarmKey = alarmKey;
            return this;
        }

        public Builder withFeedbackType(AlarmFeedback.FeedbackType feedbackType) {
            this.feedbackType = feedbackType;
            return this;
        }

        public Builder withReason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder withUser(String user) {
            this.user = user;
            return this;
        }

        public Builder withTimestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public AlarmFeedback build() {
            Objects.requireNonNull(situationKey, "The situation key cannot be null");
            Objects.requireNonNull(alarmKey, "The alarm key cannot be null");
            Objects.requireNonNull(feedbackType, "The feedback type cannot be null");

            return new AlarmFeedback(this);            
        }
    }
    
    public static Builder newBuilder() {
        return new Builder();
    }

    public String getSituationKey() {
        return situationKey;
    }

    public String getSituationFingerprint() {
        return situationFingerprint;
    }

    public String getAlarmKey() {
        return alarmKey;
    }

    public FeedbackType getFeedbackType() {
        return feedbackType;
    }

    public String getReason() {
        return reason;
    }

    public String getUser() {
        return user;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "Feedback[" + getFeedbackType() + ":" + getSituationKey() + ":" + getAlarmKey() + ":" + getReason() +
                "]";
    }

}
