/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients.consumer.internals;

import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.test.TestUtils;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.Collections.singleton;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SubscriptionStateTest {

    private final SubscriptionState state = new SubscriptionState(
            new LogContext(),
            OffsetResetStrategy.EARLIEST);
    private final String topic = "test";
    private final String topic1 = "test1";
    private final TopicPartition tp0 = new TopicPartition(topic, 0);
    private final TopicPartition tp1 = new TopicPartition(topic, 1);
    private final TopicPartition t1p0 = new TopicPartition(topic1, 0);
    private final MockRebalanceListener rebalanceListener = new MockRebalanceListener();
    private final Metadata.LeaderAndEpoch leaderAndEpoch = new Metadata.LeaderAndEpoch(Node.noNode(), Optional.empty());

    @Test
    public void partitionAssignment() {
        state.assignFromUser(singleton(tp0));
        assertEquals(singleton(tp0), state.assignedPartitions());
        assertEquals(1, state.numAssignedPartitions());
        assertFalse(state.hasAllFetchPositions());
        state.seek(tp0, 1);
        assertTrue(state.isFetchable(tp0));
        assertEquals(1L, state.position(tp0).offset);
        state.assignFromUser(Collections.<TopicPartition>emptySet());
        assertTrue(state.assignedPartitions().isEmpty());
        assertEquals(0, state.numAssignedPartitions());
        assertFalse(state.isAssigned(tp0));
        assertFalse(state.isFetchable(tp0));
    }

    @Test
    public void partitionAssignmentChangeOnTopicSubscription() {
        state.assignFromUser(new HashSet<>(Arrays.asList(tp0, tp1)));
        // assigned partitions should immediately change
        assertEquals(2, state.assignedPartitions().size());
        assertEquals(2, state.numAssignedPartitions());
        assertTrue(state.assignedPartitions().contains(tp0));
        assertTrue(state.assignedPartitions().contains(tp1));

        state.unsubscribe();
        // assigned partitions should immediately change
        assertTrue(state.assignedPartitions().isEmpty());
        assertEquals(0, state.numAssignedPartitions());

        state.subscribe(singleton(topic1), rebalanceListener);
        // assigned partitions should remain unchanged
        assertTrue(state.assignedPartitions().isEmpty());
        assertEquals(0, state.numAssignedPartitions());

        assertTrue(state.assignFromSubscribed(singleton(t1p0)));
        // assigned partitions should immediately change
        assertEquals(singleton(t1p0), state.assignedPartitions());
        assertEquals(1, state.numAssignedPartitions());

        state.subscribe(singleton(topic), rebalanceListener);
        // assigned partitions should remain unchanged
        assertEquals(singleton(t1p0), state.assignedPartitions());
        assertEquals(1, state.numAssignedPartitions());

        state.unsubscribe();
        // assigned partitions should immediately change
        assertTrue(state.assignedPartitions().isEmpty());
        assertEquals(0, state.numAssignedPartitions());
    }

    @Test
    public void partitionAssignmentChangeOnPatternSubscription() {
        state.subscribe(Pattern.compile(".*"), rebalanceListener);
        // assigned partitions should remain unchanged
        assertTrue(state.assignedPartitions().isEmpty());
        assertEquals(0, state.numAssignedPartitions());

        state.subscribeFromPattern(new HashSet<>(Collections.singletonList(topic)));
        // assigned partitions should remain unchanged
        assertTrue(state.assignedPartitions().isEmpty());
        assertEquals(0, state.numAssignedPartitions());

        assertTrue(state.assignFromSubscribed(singleton(tp1)));
        // assigned partitions should immediately change
        assertEquals(singleton(tp1), state.assignedPartitions());
        assertEquals(1, state.numAssignedPartitions());
        assertEquals(singleton(topic), state.subscription());

        assertTrue(state.assignFromSubscribed(Collections.singletonList(t1p0)));
        // assigned partitions should immediately change
        assertEquals(singleton(t1p0), state.assignedPartitions());
        assertEquals(1, state.numAssignedPartitions());
        assertEquals(singleton(topic), state.subscription());

        state.subscribe(Pattern.compile(".*t"), rebalanceListener);
        // assigned partitions should remain unchanged
        assertEquals(singleton(t1p0), state.assignedPartitions());
        assertEquals(1, state.numAssignedPartitions());

        state.subscribeFromPattern(singleton(topic));
        // assigned partitions should remain unchanged
        assertEquals(singleton(t1p0), state.assignedPartitions());
        assertEquals(1, state.numAssignedPartitions());

        assertTrue(state.assignFromSubscribed(Collections.singletonList(tp0)));
        // assigned partitions should immediately change
        assertEquals(singleton(tp0), state.assignedPartitions());
        assertEquals(1, state.numAssignedPartitions());
        assertEquals(singleton(topic), state.subscription());

        state.unsubscribe();
        // assigned partitions should immediately change
        assertTrue(state.assignedPartitions().isEmpty());
        assertEquals(0, state.numAssignedPartitions());
    }

    @Test
    public void verifyAssignmentId() {
        assertEquals(0, state.assignmentId());
        Set<TopicPartition> userAssignment = Utils.mkSet(tp0, tp1);
        state.assignFromUser(userAssignment);
        assertEquals(1, state.assignmentId());
        assertEquals(userAssignment, state.assignedPartitions());

        state.unsubscribe();
        assertEquals(2, state.assignmentId());
        assertEquals(Collections.emptySet(), state.assignedPartitions());

        Set<TopicPartition> autoAssignment = Utils.mkSet(t1p0);
        state.subscribe(singleton(topic1), rebalanceListener);
        assertTrue(state.assignFromSubscribed(autoAssignment));
        assertEquals(3, state.assignmentId());
        assertEquals(autoAssignment, state.assignedPartitions());
    }

    @Test
    public void partitionReset() {
        state.assignFromUser(singleton(tp0));
        state.seek(tp0, 5);
        assertEquals(5L, state.position(tp0).offset);
        state.requestOffsetReset(tp0);
        assertFalse(state.isFetchable(tp0));
        assertTrue(state.isOffsetResetNeeded(tp0));
        assertNotNull(state.position(tp0));

        // seek should clear the reset and make the partition fetchable
        state.seek(tp0, 0);
        assertTrue(state.isFetchable(tp0));
        assertFalse(state.isOffsetResetNeeded(tp0));
    }

    @Test
    public void topicSubscription() {
        state.subscribe(singleton(topic), rebalanceListener);
        assertEquals(1, state.subscription().size());
        assertTrue(state.assignedPartitions().isEmpty());
        assertEquals(0, state.numAssignedPartitions());
        assertTrue(state.partitionsAutoAssigned());
        assertTrue(state.assignFromSubscribed(singleton(tp0)));
        state.seek(tp0, 1);
        assertEquals(1L, state.position(tp0).offset);
        assertTrue(state.assignFromSubscribed(singleton(tp1)));
        assertTrue(state.isAssigned(tp1));
        assertFalse(state.isAssigned(tp0));
        assertFalse(state.isFetchable(tp1));
        assertEquals(singleton(tp1), state.assignedPartitions());
        assertEquals(1, state.numAssignedPartitions());
    }

    @Test
    public void partitionPause() {
        state.assignFromUser(singleton(tp0));
        state.seek(tp0, 100);
        assertTrue(state.isFetchable(tp0));
        state.pause(tp0);
        assertFalse(state.isFetchable(tp0));
        state.resume(tp0);
        assertTrue(state.isFetchable(tp0));
    }

    @Test(expected = IllegalStateException.class)
    public void invalidPositionUpdate() {
        state.subscribe(singleton(topic), rebalanceListener);
        assertTrue(state.assignFromSubscribed(singleton(tp0)));
        state.position(tp0, new SubscriptionState.FetchPosition(0, Optional.empty(), leaderAndEpoch));
    }

    @Test
    public void cantAssignPartitionForUnsubscribedTopics() {
        state.subscribe(singleton(topic), rebalanceListener);
        assertFalse(state.assignFromSubscribed(Collections.singletonList(t1p0)));
    }

    @Test
    public void cantAssignPartitionForUnmatchedPattern() {
        state.subscribe(Pattern.compile(".*t"), rebalanceListener);
        state.subscribeFromPattern(new HashSet<>(Collections.singletonList(topic)));
        assertFalse(state.assignFromSubscribed(Collections.singletonList(t1p0)));
    }

    @Test(expected = IllegalStateException.class)
    public void cantChangePositionForNonAssignedPartition() {
        state.position(tp0, new SubscriptionState.FetchPosition(1, Optional.empty(), leaderAndEpoch));
    }

    @Test(expected = IllegalStateException.class)
    public void cantSubscribeTopicAndPattern() {
        state.subscribe(singleton(topic), rebalanceListener);
        state.subscribe(Pattern.compile(".*"), rebalanceListener);
    }

    @Test(expected = IllegalStateException.class)
    public void cantSubscribePartitionAndPattern() {
        state.assignFromUser(singleton(tp0));
        state.subscribe(Pattern.compile(".*"), rebalanceListener);
    }

    @Test(expected = IllegalStateException.class)
    public void cantSubscribePatternAndTopic() {
        state.subscribe(Pattern.compile(".*"), rebalanceListener);
        state.subscribe(singleton(topic), rebalanceListener);
    }

    @Test(expected = IllegalStateException.class)
    public void cantSubscribePatternAndPartition() {
        state.subscribe(Pattern.compile(".*"), rebalanceListener);
        state.assignFromUser(singleton(tp0));
    }

    @Test
    public void patternSubscription() {
        state.subscribe(Pattern.compile(".*"), rebalanceListener);
        state.subscribeFromPattern(new HashSet<>(Arrays.asList(topic, topic1)));
        assertEquals("Expected subscribed topics count is incorrect", 2, state.subscription().size());
    }

    @Test
    public void unsubscribeUserAssignment() {
        state.assignFromUser(new HashSet<>(Arrays.asList(tp0, tp1)));
        state.unsubscribe();
        state.subscribe(singleton(topic), rebalanceListener);
        assertEquals(singleton(topic), state.subscription());
    }

    @Test
    public void unsubscribeUserSubscribe() {
        state.subscribe(singleton(topic), rebalanceListener);
        state.unsubscribe();
        state.assignFromUser(singleton(tp0));
        assertEquals(singleton(tp0), state.assignedPartitions());
        assertEquals(1, state.numAssignedPartitions());
    }

    @Test
    public void unsubscription() {
        state.subscribe(Pattern.compile(".*"), rebalanceListener);
        state.subscribeFromPattern(new HashSet<>(Arrays.asList(topic, topic1)));
        assertTrue(state.assignFromSubscribed(singleton(tp1)));
        assertEquals(singleton(tp1), state.assignedPartitions());
        assertEquals(1, state.numAssignedPartitions());

        state.unsubscribe();
        assertEquals(0, state.subscription().size());
        assertTrue(state.assignedPartitions().isEmpty());
        assertEquals(0, state.numAssignedPartitions());

        state.assignFromUser(singleton(tp0));
        assertEquals(singleton(tp0), state.assignedPartitions());
        assertEquals(1, state.numAssignedPartitions());

        state.unsubscribe();
        assertEquals(0, state.subscription().size());
        assertTrue(state.assignedPartitions().isEmpty());
        assertEquals(0, state.numAssignedPartitions());
    }

    @Test
    public void testPreferredReadReplicaLease() {
        state.assignFromUser(Collections.singleton(tp0));

        // Default state
        assertFalse(state.preferredReadReplica(tp0, 0L).isPresent());

        // Set the preferred replica with lease
        state.updatePreferredReadReplica(tp0, 42, () -> 10L);
        TestUtils.assertOptional(state.preferredReadReplica(tp0, 9L),  value -> assertEquals(value.intValue(), 42));
        TestUtils.assertOptional(state.preferredReadReplica(tp0, 10L),  value -> assertEquals(value.intValue(), 42));
        assertFalse(state.preferredReadReplica(tp0, 11L).isPresent());

        // Unset the preferred replica
        state.clearPreferredReadReplica(tp0);
        assertFalse(state.preferredReadReplica(tp0, 9L).isPresent());
        assertFalse(state.preferredReadReplica(tp0, 11L).isPresent());

        // Set to new preferred replica with lease
        state.updatePreferredReadReplica(tp0, 43, () -> 20L);
        TestUtils.assertOptional(state.preferredReadReplica(tp0, 11L),  value -> assertEquals(value.intValue(), 43));
        TestUtils.assertOptional(state.preferredReadReplica(tp0, 20L),  value -> assertEquals(value.intValue(), 43));
        assertFalse(state.preferredReadReplica(tp0, 21L).isPresent());

        // Set to new preferred replica without clearing first
        state.updatePreferredReadReplica(tp0, 44, () -> 30L);
        TestUtils.assertOptional(state.preferredReadReplica(tp0, 30L),  value -> assertEquals(value.intValue(), 44));
        assertFalse(state.preferredReadReplica(tp0, 31L).isPresent());
    }

    private static class MockRebalanceListener implements ConsumerRebalanceListener {
        public Collection<TopicPartition> revoked;
        public Collection<TopicPartition> assigned;
        public int revokedCount = 0;
        public int assignedCount = 0;


        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            this.assigned = partitions;
            assignedCount++;
        }

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            this.revoked = partitions;
            revokedCount++;
        }

    }

}
