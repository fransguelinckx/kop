/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.pulsar.handlers.kop.coordinator.group;

import io.streamnative.pulsar.handlers.kop.offset.OffsetAndMetadata;
import io.streamnative.pulsar.handlers.kop.utils.MessageIdUtils;
import io.streamnative.pulsar.handlers.kop.utils.TopicNameUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerPartitionAssignor;
import org.apache.kafka.clients.consumer.internals.ConsumerProtocol;
import org.apache.kafka.common.TopicPartition;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.ReaderBuilder;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.client.impl.ReaderBuilderImpl;
import org.apache.pulsar.client.impl.ReaderImpl;
import org.apache.pulsar.common.naming.TopicName;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class used to track all the partition offset commit position.
 */
@Slf4j
public class OffsetAcker implements Closeable {

    private final ReaderBuilder<byte[]> readerBuilder;

    public OffsetAcker(PulsarClientImpl pulsarClient) {
        this.readerBuilder = new ReaderBuilderImpl<>(pulsarClient, Schema.BYTES)
            .receiverQueueSize(0)
            .startMessageId(MessageId.earliest);
    }

    // map off consumser: <groupId, consumers>
    Map<String, Map<TopicPartition, CompletableFuture<Consumer<byte[]>>>> consumers = new ConcurrentHashMap<>();

    public void addOffsetsTracker(String groupId, byte[] assignment) {
        ByteBuffer assignBuffer = ByteBuffer.wrap(assignment);
        ConsumerPartitionAssignor.Assignment assign = ConsumerProtocol.deserializeAssignment(assignBuffer);
        if (log.isDebugEnabled()) {
            log.debug(" Add offsets after sync group: {}", assign.toString());
        }
        assign.partitions().forEach(topicPartition -> getConsumer(groupId, topicPartition));
    }

    public void ackOffsets(String groupId, Map<TopicPartition, OffsetAndMetadata> offsetMetadata) {
        if (log.isDebugEnabled()) {
            log.debug(" ack offsets after commit offset for group: {}", groupId);
            offsetMetadata.forEach((partition, metadata) ->
                log.debug("\t partition: {}, offset: {}",
                    partition, MessageIdUtils.getPosition(metadata.offset())));
        }
        offsetMetadata.forEach(((topicPartition, offsetAndMetadata) -> {
            // 1. get consumer, then do ackCumulative
            CompletableFuture<Consumer<byte[]>> consumerFuture = getConsumer(groupId, topicPartition);

            consumerFuture.whenComplete((consumer, throwable) -> {
                if (throwable != null) {
                    log.warn("Error when get consumer for offset ack:", throwable);
                    return;
                }
                MessageId messageId = MessageIdUtils.getMessageId(offsetAndMetadata.offset());
                consumer.acknowledgeCumulativeAsync(messageId);
            });
        }));
    }

    public void close(Set<String> groupIds) {
        groupIds.forEach(groupId -> consumers.get(groupId).values().forEach(consumerFuture -> {
            consumerFuture.whenComplete((consumer, throwable) -> {
                if (throwable != null) {
                    log.warn("Error when get consumer for consumer group close:", throwable);
                    return;
                }
                try {
                    consumer.close();
                } catch (Exception e) {
                    log.warn("Error when close consumer topic: {}, sub: {}.",
                        consumer.getTopic(), consumer.getSubscription(), e);
                }
            });
        }));
    }

    @Override
    public void close() {
        log.info("close OffsetAcker with {} groupIds", consumers.size());
        close(consumers.keySet());
    }

    private CompletableFuture<Consumer<byte[]>> getConsumer(String groupId, TopicPartition topicPartition) {
        Map<TopicPartition, CompletableFuture<Consumer<byte[]>>> group = consumers
            .computeIfAbsent(groupId, gid -> new ConcurrentHashMap<>());
        return group.computeIfAbsent(
            topicPartition,
            partition -> createConsumer(groupId, partition));
    }

    private CompletableFuture<Consumer<byte[]>> createConsumer(String groupId, TopicPartition topicPartition) {
        TopicName pulsarTopicName = TopicNameUtils.pulsarTopicName(topicPartition);
        return readerBuilder.clone()
            .topic(pulsarTopicName.toString())
            .subscriptionRolePrefix(groupId)
            .createAsync()
            .thenApply(reader -> ((ReaderImpl<byte[]>) reader).getConsumer());
    }

}
