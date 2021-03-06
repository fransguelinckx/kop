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
package io.streamnative.pulsar.handlers.kop;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import io.netty.channel.ChannelHandlerContext;
import io.streamnative.pulsar.handlers.kop.coordinator.group.GroupCoordinator;
import io.streamnative.pulsar.handlers.kop.coordinator.group.GroupMetadata.GroupOverview;
import io.streamnative.pulsar.handlers.kop.coordinator.group.GroupMetadata.GroupSummary;
import io.streamnative.pulsar.handlers.kop.offset.OffsetAndMetadata;
import io.streamnative.pulsar.handlers.kop.utils.CoreUtils;
import io.streamnative.pulsar.handlers.kop.utils.MessageIdUtils;
import io.streamnative.pulsar.handlers.kop.utils.OffsetFinder;
import io.streamnative.pulsar.handlers.kop.utils.SaslUtils;
import io.streamnative.pulsar.handlers.kop.utils.TopicNameUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.bookkeeper.mledger.AsyncCallbacks;
import org.apache.bookkeeper.mledger.ManagedLedgerException;
import org.apache.bookkeeper.mledger.Position;
import org.apache.bookkeeper.mledger.impl.ManagedLedgerImpl;
import org.apache.bookkeeper.mledger.impl.PositionImpl;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.LeaderNotAvailableException;
import org.apache.kafka.common.internals.Topic;
import org.apache.kafka.common.message.ApiVersionsResponseData;
import org.apache.kafka.common.message.DeleteGroupsResponseData;
import org.apache.kafka.common.message.DescribeGroupsResponseData;
import org.apache.kafka.common.message.FindCoordinatorResponseData;
import org.apache.kafka.common.message.HeartbeatResponseData;
import org.apache.kafka.common.message.JoinGroupResponseData;
import org.apache.kafka.common.message.LeaveGroupResponseData;
import org.apache.kafka.common.message.ListGroupsResponseData;
import org.apache.kafka.common.message.MetadataResponseData;
import org.apache.kafka.common.message.SaslAuthenticateResponseData;
import org.apache.kafka.common.message.SaslHandshakeResponseData;
import org.apache.kafka.common.message.SyncGroupRequestData;
import org.apache.kafka.common.message.SyncGroupResponseData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.record.Records;
import org.apache.kafka.common.requests.AbstractRequest;
import org.apache.kafka.common.requests.AbstractResponse;
import org.apache.kafka.common.requests.ApiVersionsResponse;
import org.apache.kafka.common.requests.DeleteGroupsRequest;
import org.apache.kafka.common.requests.DeleteGroupsResponse;
import org.apache.kafka.common.requests.DescribeGroupsRequest;
import org.apache.kafka.common.requests.DescribeGroupsResponse;
import org.apache.kafka.common.requests.FetchRequest;
import org.apache.kafka.common.requests.FindCoordinatorRequest;
import org.apache.kafka.common.requests.FindCoordinatorResponse;
import org.apache.kafka.common.requests.HeartbeatRequest;
import org.apache.kafka.common.requests.HeartbeatResponse;
import org.apache.kafka.common.requests.JoinGroupRequest;
import org.apache.kafka.common.requests.JoinGroupResponse;
import org.apache.kafka.common.requests.LeaveGroupRequest;
import org.apache.kafka.common.requests.LeaveGroupResponse;
import org.apache.kafka.common.requests.ListGroupsRequest;
import org.apache.kafka.common.requests.ListGroupsResponse;
import org.apache.kafka.common.requests.ListOffsetRequest;
import org.apache.kafka.common.requests.ListOffsetResponse;
import org.apache.kafka.common.requests.MetadataRequest;
import org.apache.kafka.common.requests.MetadataResponse;
import org.apache.kafka.common.requests.MetadataResponse.PartitionMetadata;
import org.apache.kafka.common.requests.OffsetCommitRequest;
import org.apache.kafka.common.requests.OffsetCommitResponse;
import org.apache.kafka.common.requests.OffsetFetchRequest;
import org.apache.kafka.common.requests.OffsetFetchResponse;
import org.apache.kafka.common.requests.ProduceRequest;
import org.apache.kafka.common.requests.ProduceResponse;
import org.apache.kafka.common.requests.ProduceResponse.PartitionResponse;
import org.apache.kafka.common.requests.SaslAuthenticateRequest;
import org.apache.kafka.common.requests.SaslAuthenticateResponse;
import org.apache.kafka.common.requests.SaslHandshakeRequest;
import org.apache.kafka.common.requests.SaslHandshakeResponse;
import org.apache.kafka.common.requests.SyncGroupRequest;
import org.apache.kafka.common.requests.SyncGroupResponse;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.ServiceConfigurationUtils;
import org.apache.pulsar.broker.authentication.AuthenticationProvider;
import org.apache.pulsar.broker.authentication.AuthenticationService;
import org.apache.pulsar.broker.authentication.AuthenticationState;
import org.apache.pulsar.broker.loadbalance.LoadManager;
import org.apache.pulsar.broker.service.persistent.PersistentTopic;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.PulsarClientException.AuthorizationException;
import org.apache.pulsar.common.api.AuthData;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.naming.TopicDomain;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.partition.PartitionedTopicMetadata;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.schema.KeyValue;
import org.apache.pulsar.common.util.FutureUtil;
import org.apache.pulsar.common.util.Murmur3_32Hash;
import org.apache.pulsar.policies.data.loadbalancer.ServiceLookupData;
import org.apache.pulsar.zookeeper.ZooKeeperCache;
import org.apache.pulsar.zookeeper.ZooKeeperCache.Deserializer;

import javax.naming.AuthenticationException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.streamnative.pulsar.handlers.kop.KafkaProtocolHandler.ListenerType.PLAINTEXT;
import static io.streamnative.pulsar.handlers.kop.KafkaProtocolHandler.ListenerType.SSL;
import static io.streamnative.pulsar.handlers.kop.KafkaProtocolHandler.getKopBrokerUrl;
import static io.streamnative.pulsar.handlers.kop.KafkaProtocolHandler.getListenerPort;
import static io.streamnative.pulsar.handlers.kop.MessagePublishContext.publishMessages;
import static io.streamnative.pulsar.handlers.kop.utils.TopicNameUtils.pulsarTopicName;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.apache.kafka.common.protocol.CommonFields.THROTTLE_TIME_MS;
import static org.apache.kafka.common.protocol.Errors.NONE;
import static org.apache.kafka.common.protocol.Errors.NOT_LEADER_FOR_PARTITION;
import static org.apache.kafka.common.protocol.Errors.UNKNOWN_TOPIC_OR_PARTITION;
import static org.apache.pulsar.common.naming.TopicName.PARTITIONED_TOPIC_SUFFIX;

/**
 * This class contains all the request handling methods.
 */
@Slf4j
@Getter
public class KafkaRequestHandler extends KafkaCommandDecoder {
    public static final long DEFAULT_TIMESTAMP = 0L;

    private final PulsarService pulsarService;
    private final KafkaServiceConfiguration kafkaConfig;
    private final KafkaTopicManager topicManager;
    private final GroupCoordinator groupCoordinator;

    private final String clusterName;
    private final ScheduledExecutorService executor;
    private final PulsarAdmin admin;
    private final Boolean tlsEnabled;
    private final String localListeners;
    private final int plaintextPort;
    private final int sslPort;
    private NamespaceName namespace;
    private String authRole;
    private AuthenticationState authState;

    public KafkaRequestHandler(PulsarService pulsarService,
                               KafkaServiceConfiguration kafkaConfig,
                               GroupCoordinator groupCoordinator,
                               Boolean tlsEnabled) throws Exception {
        super();
        this.pulsarService = pulsarService;
        this.kafkaConfig = kafkaConfig;
        this.groupCoordinator = groupCoordinator;
        this.clusterName = kafkaConfig.getClusterName();
        this.executor = pulsarService.getExecutor();
        this.admin = pulsarService.getAdminClient();
        this.tlsEnabled = tlsEnabled;
        this.localListeners = KafkaProtocolHandler.getListeners(kafkaConfig);
        this.plaintextPort = getListenerPort(localListeners, PLAINTEXT);
        this.sslPort = getListenerPort(localListeners, SSL);
        this.namespace = NamespaceName.get(
            kafkaConfig.getKafkaTenant(),
            kafkaConfig.getKafkaNamespace());
        this.topicManager = new KafkaTopicManager(this);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        getTopicManager().updateCtx();
        log.info("channel active: {}", ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        log.info("channel inactive {}", ctx.channel());

        close();
        isActive.set(false);
    }

    @Override
    protected void close() {
        if (isActive.getAndSet(false)) {
            log.info("close channel {}", ctx.channel());
            writeAndFlushWhenInactiveChannel(ctx.channel());
            ctx.close();
            topicManager.close();
        }
    }

    protected void handleApiVersionsRequest(KafkaHeaderAndRequest apiVersionRequest,
                                            CompletableFuture<AbstractResponse> resultFuture) {
        AbstractResponse apiResponse = overloadDefaultApiVersionsResponse();
        resultFuture.complete(apiResponse);
    }

    protected ApiVersionsResponse overloadDefaultApiVersionsResponse() {
        ApiVersionsResponseData.ApiVersionsResponseKeyCollection apiVersionsResponseKeys = new ApiVersionsResponseData.ApiVersionsResponseKeyCollection();
        for (ApiKeys apiKey : ApiKeys.values()) {
            if (apiKey.minRequiredInterBrokerMagic <= RecordBatch.CURRENT_MAGIC_VALUE) {
                switch (apiKey) {
                    case FETCH:
                        // V4 added MessageSets responses. We need to make sure RecordBatch format is not used
                        ApiVersionsResponseData.ApiVersionsResponseKey key = new ApiVersionsResponseData.ApiVersionsResponseKey();
                        key.setApiKey((short) 1);
                        key.setMinVersion((short) 4);
                        key.setMaxVersion(apiKey.latestVersion());
                        apiVersionsResponseKeys.add(key);
                        break;
                    case LIST_OFFSETS:
                        // V0 is needed for librdkafka
                        ApiVersionsResponseData.ApiVersionsResponseKey key1 = new ApiVersionsResponseData.ApiVersionsResponseKey();
                        key1.setApiKey((short) 2);
                        key1.setMinVersion((short) 0);
                        key1.setMaxVersion(apiKey.latestVersion());
                        apiVersionsResponseKeys.add(key1);
                        break;
                    default:
                        ApiVersionsResponseData.ApiVersionsResponseKey key2 = new ApiVersionsResponseData.ApiVersionsResponseKey();
                        key2.setApiKey(apiKey.id);
                        key2.setMinVersion(apiKey.oldestVersion());
                        key2.setMaxVersion(apiKey.latestVersion());
                        apiVersionsResponseKeys.add(key2);
                }
            }
        }
        ApiVersionsResponseData apiVersionsResponseData = new ApiVersionsResponseData();
        apiVersionsResponseData.setApiKeys(apiVersionsResponseKeys);
        apiVersionsResponseData.setErrorCode(Errors.NONE.code());
        apiVersionsResponseData.setThrottleTimeMs(0);

        return new ApiVersionsResponse(apiVersionsResponseData);
    }

    protected void handleError(KafkaHeaderAndRequest kafkaHeaderAndRequest,
                               CompletableFuture<AbstractResponse> resultFuture) {
        String err = String.format("Kafka API (%s) Not supported by kop server.",
            kafkaHeaderAndRequest.getHeader().apiKey());
        log.error(err);

        AbstractResponse apiResponse = kafkaHeaderAndRequest.getRequest()
            .getErrorResponse(new UnsupportedOperationException(err));
        resultFuture.complete(apiResponse);
    }

    protected void handleInactive(KafkaHeaderAndRequest kafkaHeaderAndRequest,
                                  CompletableFuture<AbstractResponse> resultFuture) {
        AbstractRequest request = kafkaHeaderAndRequest.getRequest();
        AbstractResponse apiResponse = request.getErrorResponse(new LeaderNotAvailableException("Channel is closing!"));

        log.error("Kafka API {} is send to a closing channel", kafkaHeaderAndRequest.getHeader().apiKey());

        resultFuture.complete(apiResponse);
    }

    // Leverage pulsar admin to get partitioned topic metadata
    private CompletableFuture<PartitionedTopicMetadata> getPartitionedTopicMetadataAsync(String topicName) {
        return admin.topics().getPartitionedTopicMetadataAsync(topicName);
    }

    protected void handleTopicMetadataRequest(KafkaHeaderAndRequest metadataHar,
                                              CompletableFuture<AbstractResponse> resultFuture) {
        checkArgument(metadataHar.getRequest() instanceof MetadataRequest);

        MetadataRequest metadataRequest = (MetadataRequest) metadataHar.getRequest();
        if (log.isDebugEnabled()) {
            log.debug("[{}] Request {}: for topic {} ",
                ctx.channel(), metadataHar.getHeader(), metadataRequest.topics());
        }

        // Command response for all topics
        MetadataResponseData.MetadataResponseTopicCollection allTopics = new MetadataResponseData.MetadataResponseTopicCollection();
        MetadataResponseData.MetadataResponseBrokerCollection allBrokers = new MetadataResponseData.MetadataResponseBrokerCollection();

        List<String> topics = metadataRequest.topics();
        // topics in format : persistent://%s/%s/abc-partition-x, will be grouped by as:
        //      Entry<abc, List[TopicName]>

        // A future for a map from <kafka topic> to <pulsarPartitionTopics>:
        //      e.g. <topic1, {persistent://public/default/topic1-partition-0,...}>
        //   1. no topics provided, get all topics from namespace;
        //   2. topics provided, get provided topics.
        CompletableFuture<Map<String, List<TopicName>>> pulsarTopicsFuture =
            (topics == null || topics.isEmpty())
                ?
                pulsarService.getNamespaceService().getListOfPersistentTopics(namespace)
                    .thenApply(
                        list -> list.stream()
                            .map(TopicName::get)
                            .collect(Collectors
                                .groupingBy(
                                    TopicNameUtils::getKafkaTopicNameFromPulsarTopicname,
                                    toList()))
                    )
                :
                new CompletableFuture<>();

        if (!(topics == null || topics.isEmpty())) {
            Map<String, List<TopicName>> pulsarTopics = Maps.newHashMap();

            List<String> requestTopics = metadataRequest.topics();
            final int topicsNumber = requestTopics.size();
            AtomicInteger topicsCompleted = new AtomicInteger(0);

            requestTopics.stream()
                .forEach(topic -> {

                    TopicName pulsarTopicName;
                    if (topic.startsWith(TopicDomain.persistent.value()) && topic.contains(namespace.getLocalName())) {
                        pulsarTopicName = pulsarTopicName(topic);
                    } else {
                        pulsarTopicName = pulsarTopicName(topic, namespace);
                    }

                    // get partition numbers for each topic.
                    getPartitionedTopicMetadataAsync(pulsarTopicName.toString())
                        .whenComplete((partitionedTopicMetadata, throwable) -> {
                            if (throwable != null) {
                                // Failed get partitions.
                                MetadataResponseData.MetadataResponseTopic metadataResponseTopic = new MetadataResponseData.MetadataResponseTopic();
                                metadataResponseTopic.setErrorCode(UNKNOWN_TOPIC_OR_PARTITION.code());
                                metadataResponseTopic.setName(topic);
                                metadataResponseTopic.setIsInternal(false);
                                metadataResponseTopic.setPartitions(emptyList());
                                allTopics.add(metadataResponseTopic);
                                log.warn("[{}] Request {}: Failed to get partitioned pulsar topic {} metadata: {}",
                                    ctx.channel(), metadataHar.getHeader(), pulsarTopicName, throwable.getMessage());
                            } else {
                                List<TopicName> pulsarTopicNames;
                                if (partitionedTopicMetadata.partitions > 0) {
                                    if (log.isDebugEnabled()) {
                                        log.debug("Topic {} has {} partitions",
                                            topic, partitionedTopicMetadata.partitions);
                                    }
                                    pulsarTopicNames = IntStream
                                        .range(0, partitionedTopicMetadata.partitions)
                                        .mapToObj(i ->
                                            TopicName.get(pulsarTopicName.toString() + PARTITIONED_TOPIC_SUFFIX + i))
                                        .collect(toList());
                                    pulsarTopics.put(topic, pulsarTopicNames);
                                } else {
                                    if (kafkaConfig.isAllowAutoTopicCreation()) {
                                        if (log.isDebugEnabled()) {
                                            log.debug("[{}] Request {}: Topic {} has single partition, "
                                                    + "auto create partitioned topic",
                                                ctx.channel(), metadataHar.getHeader(), topic);
                                        }
                                        admin.topics().createPartitionedTopicAsync(pulsarTopicName.toString(), 1);
                                        final TopicName newTopic = TopicName
                                            .get(pulsarTopicName.toString() + PARTITIONED_TOPIC_SUFFIX + 0);
                                        pulsarTopics.put(topic, Lists.newArrayList(newTopic));

                                    } else {
                                        log.error("[{}] Request {}: Topic {} has single partition, "
                                                + "Not allow to auto create partitioned topic",
                                            ctx.channel(), metadataHar.getHeader(), topic);
                                        // not allow to auto create topic, return unknown topic
                                        MetadataResponseData.MetadataResponseTopic metadataResponseTopic = new MetadataResponseData.MetadataResponseTopic();
                                        metadataResponseTopic.setErrorCode(UNKNOWN_TOPIC_OR_PARTITION.code());
                                        metadataResponseTopic.setName(topic);
                                        metadataResponseTopic.setIsInternal(false);
                                        metadataResponseTopic.setPartitions(emptyList());
                                        allTopics.add(metadataResponseTopic);
                                    }
                                }
                            }

                            // whether handled all topics get partitions
                            int completedTopics = topicsCompleted.incrementAndGet();
                            if (completedTopics == topicsNumber) {
                                if (log.isDebugEnabled()) {
                                    log.debug("[{}] Request {}: Completed get {} topic's partitions",
                                        ctx.channel(), metadataHar.getHeader(), topicsNumber);
                                }
                                pulsarTopicsFuture.complete(pulsarTopics);
                            }
                        });
                });
        }

        // 2. After get all topics, for each topic, get the service Broker for it, and add to response
        AtomicInteger topicsCompleted = new AtomicInteger(0);
        pulsarTopicsFuture.whenComplete((pulsarTopics, e) -> {
            if (e != null) {
                log.warn("[{}] Request {}: Exception fetching metadata, will return null Response",
                    ctx.channel(), metadataHar.getHeader(), e);
                allBrokers.add(newSelfBroker());
                MetadataResponseData metadataResponseData = new MetadataResponseData();
                metadataResponseData.setClusterId(clusterName);
                metadataResponseData.setClusterAuthorizedOperations(MetadataResponse.NO_CONTROLLER_ID);
                metadataResponseData.setBrokers(allBrokers);
                MetadataResponse finalResponse = new MetadataResponse(metadataResponseData);
                resultFuture.complete(finalResponse);
                return;
            }

            final int topicsNumber = pulsarTopics.size();

            if (topicsNumber == 0) {
                // no topic partitions added, return now.
                allBrokers.add(newSelfBroker());
                MetadataResponseData metadataResponseData = new MetadataResponseData();
                metadataResponseData.setClusterId(clusterName);
                metadataResponseData.setClusterAuthorizedOperations(MetadataResponse.NO_CONTROLLER_ID);
                metadataResponseData.setBrokers(allBrokers);
                metadataResponseData.setTopics(allTopics);
                MetadataResponse finalResponse = new MetadataResponse(metadataResponseData);
                resultFuture.complete(finalResponse);
                return;
            }

            pulsarTopics.forEach((topic, list) -> {
                final int partitionsNumber = list.size();
                AtomicInteger partitionsCompleted = new AtomicInteger(0);
                List<MetadataResponseData.MetadataResponsePartition> metadataResponsePartitions =
                    Collections.synchronizedList(Lists.newArrayListWithExpectedSize(partitionsNumber));

                list.forEach(topicName ->
                    findBroker(topicName)
                        .whenComplete(((partitionMetadata, throwable) -> {
                            if (throwable != null || partitionMetadata == null) {
                                log.warn("[{}] Request {}: Exception while find Broker metadata",
                                    ctx.channel(), metadataHar.getHeader(), throwable);
                                metadataResponsePartitions.add(newFailedMetadataResponsePartition(topicName));

                            } else {
                                Node newNode = partitionMetadata.leader();
                                synchronized (allBrokers) {
                                    if (allBrokers.stream().noneMatch(node1 -> node1.nodeId() == newNode.id())) {
                                        MetadataResponseData.MetadataResponseBroker leader = new MetadataResponseData.MetadataResponseBroker();
                                        leader.setNodeId(newNode.id());
                                        leader.setPort(newNode.port());
                                        leader.setHost(newNode.host());
                                        leader.setRack(newNode.rack());
                                        allBrokers.add(leader);
                                    }
                                }
                                MetadataResponseData.MetadataResponsePartition metadataResponsePartition = new MetadataResponseData.MetadataResponsePartition();
                                metadataResponsePartition.setLeaderId(partitionMetadata.leader().id());
                                metadataResponsePartition.setOfflineReplicas(partitionMetadata.offlineReplicas().stream().map(Node::id).collect(toList()));
                                metadataResponsePartition.setPartitionIndex(partitionMetadata.partition());
                                metadataResponsePartition.setIsrNodes(partitionMetadata.isr().stream().map(Node::id).collect(toList()));
                                metadataResponsePartition.setLeaderEpoch(partitionMetadata.leaderEpoch().orElse(0));
                                metadataResponsePartition.setReplicaNodes(partitionMetadata.replicas().stream().map(Node::id).collect(toList()));
                                metadataResponsePartition.setErrorCode(partitionMetadata.error().code());
                                metadataResponsePartitions.add(metadataResponsePartition);
                            }

                            // whether completed this topic's partitions list.
                            int finishedPartitions = partitionsCompleted.incrementAndGet();
                            if (log.isDebugEnabled()) {
                                log.debug("[{}] Request {}: FindBroker for topic {}, partitions found/all: {}/{}.",
                                    ctx.channel(), metadataHar.getHeader(),
                                    topic, finishedPartitions, partitionsNumber);
                            }
                            if (finishedPartitions == partitionsNumber) {
                                // new TopicMetadata for this topic
                                MetadataResponseData.MetadataResponseTopic metadataResponseTopic = new MetadataResponseData.MetadataResponseTopic();
                                metadataResponseTopic.setErrorCode(NONE.code());
                                // we should answer with the right name, either local of full-name,
                                // depending on what was asked
                                metadataResponseTopic.setName(topic.startsWith("persistent://")
                                    ? TopicName.get(topic).toString() : TopicName.get(topic).getLocalName());
                                metadataResponseTopic.setIsInternal(false);
                                metadataResponseTopic.setPartitions(metadataResponsePartitions);
                                allTopics.add(metadataResponseTopic);

                                // whether completed all the topics requests.
                                int finishedTopics = topicsCompleted.incrementAndGet();
                                if (log.isDebugEnabled()) {
                                    log.debug("[{}] Request {}: Completed findBroker for topic {}, "
                                            + "partitions found/all: {}/{}. \n dump All Metadata:",
                                        ctx.channel(), metadataHar.getHeader(), topic,
                                        finishedTopics, topicsNumber);

                                    allTopics
                                        .forEach(data -> log.debug("TopicMetadata response: {}", data.toString()));
                                }
                                if (finishedTopics == topicsNumber) {
                                    // TODO: confirm right value for controller_id
                                    MetadataResponseData metadataResponseData = new MetadataResponseData();
                                    metadataResponseData.setClusterId(clusterName);
                                    metadataResponseData.setClusterAuthorizedOperations(MetadataResponse.NO_CONTROLLER_ID);
                                    metadataResponseData.setBrokers(allBrokers);
                                    metadataResponseData.setTopics(allTopics);
                                    MetadataResponse finalResponse = new MetadataResponse(metadataResponseData);
                                    resultFuture.complete(finalResponse);
                                }
                            }
                        })));
            });
        });
    }

    // handle produce request one by one, so the produced MessageId is in order.
    private Queue<Pair<KafkaHeaderAndRequest, CompletableFuture<AbstractResponse>>> produceRequestsQueue = Queues
        .newConcurrentLinkedQueue();
    // whether the head of queue is running.
    private AtomicBoolean isHeadRequestRun = new AtomicBoolean(false);

    private void handleProducerRequestInternal() {
        // the first request that success set to running, get running.
        if (produceRequestsQueue.isEmpty() || !isHeadRequestRun.compareAndSet(false, true)) {
            // the head of queue is already running, when head complete, it will peek the following request to run.
            if (log.isDebugEnabled()) {
                log.debug(" Produce messages not entered. queue.size: {}, head isHeadRequestRun: {}",
                    produceRequestsQueue.size(), isHeadRequestRun.get());
            }
            return;
        }

        Pair<KafkaHeaderAndRequest, CompletableFuture<AbstractResponse>> head = produceRequestsQueue.peek();
        KafkaHeaderAndRequest produceHar = head.getKey();
        CompletableFuture<AbstractResponse> resultFuture = head.getValue();
        ProduceRequest produceRequest = (ProduceRequest) produceHar.getRequest();

        // Ignore request.acks() and request.timeout(), which related to kafka replication in this broker.
        Map<TopicPartition, CompletableFuture<PartitionResponse>> responsesFutures = new HashMap<>();

        final int responsesSize = produceRequest.partitionRecordsOrFail().size();

        // TODO: handle un-exist topic:
        //     nonExistingTopicResponses += topicPartition -> new PartitionResponse(Errors.UNKNOWN_TOPIC_OR_PARTITION)
        for (Map.Entry<TopicPartition, ? extends Records> entry : produceRequest.partitionRecordsOrFail().entrySet()) {
            TopicPartition topicPartition = entry.getKey();

            CompletableFuture<PartitionResponse> partitionResponse = new CompletableFuture<>();
            responsesFutures.put(topicPartition, partitionResponse);

            if (log.isDebugEnabled()) {
                log.debug("[{}] Request {}: Produce messages for topic {} partition {}, request size: {} ",
                    ctx.channel(), produceHar.getHeader(),
                    topicPartition.topic(), topicPartition.partition(), responsesSize);
            }

            TopicName topicName = pulsarTopicName(topicPartition, namespace);

            topicManager.getTopic(topicName.toString()).whenComplete((persistentTopic, exception) -> {
                if (exception != null || persistentTopic == null) {
                    log.warn("[{}] Request {}: Failed to getOrCreateTopic {}. "
                            + "Topic is in loading status, return LEADER_NOT_AVAILABLE. exception:",
                        ctx.channel(), produceHar.getHeader(), topicName, exception);
                    partitionResponse.complete(new PartitionResponse(Errors.LEADER_NOT_AVAILABLE));
                } else {
                    CompletableFuture<PersistentTopic> topicFuture = new CompletableFuture<>();
                    topicFuture.complete(persistentTopic);
                    publishMessages((MemoryRecords) entry.getValue(), persistentTopic, partitionResponse);
                }
            });
        }

        CompletableFuture.allOf(responsesFutures.values().toArray(new CompletableFuture<?>[responsesSize]))
            .whenComplete((ignore, ex) -> {
                // all ex has translated to PartitionResponse with Errors.KAFKA_STORAGE_ERROR
                Map<TopicPartition, PartitionResponse> responses = new ConcurrentHashMap<>();
                for (Map.Entry<TopicPartition, CompletableFuture<PartitionResponse>> entry :
                    responsesFutures.entrySet()) {
                    responses.put(entry.getKey(), entry.getValue().join());
                }

                if (log.isDebugEnabled()) {
                    log.debug("[{}] Request {}: Complete handle produce.",
                        ctx.channel(), produceHar.toString());
                }
                resultFuture.complete(new ProduceResponse(responses));
            });

        // trigger following request to run.
        resultFuture.whenComplete((response, throwable) -> {
            if (throwable != null) {
                log.warn("Error produce message for {}.", produceHar.getHeader(), throwable);
            }

            if (log.isDebugEnabled()) {
                log.debug("Produce messages complete. trigger next. queue.size: {}, head isHeadRequestRun: {}",
                    produceRequestsQueue.size(), isHeadRequestRun.get());
            }

            boolean compare = isHeadRequestRun.compareAndSet(true, false);
            checkState(compare, "Head should be running when completed head.");
            // remove completed request.
            produceRequestsQueue.remove();

            // trigger another run.
            handleProducerRequestInternal();
        });
    }

    protected void handleProduceRequest(KafkaHeaderAndRequest produceHar,
                                        CompletableFuture<AbstractResponse> resultFuture) {
        checkArgument(produceHar.getRequest() instanceof ProduceRequest);
        ProduceRequest produceRequest = (ProduceRequest) produceHar.getRequest();
        if (produceRequest.transactionalId() != null) {
            log.warn("[{}] Transactions not supported", ctx.channel());

            resultFuture.complete(
                failedResponse(produceHar, new UnsupportedOperationException("No transaction support")));
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug(" new produce request comes: {}, isHeadRequestRun: {}",
                produceRequestsQueue.size(), isHeadRequestRun.get());
        }
        produceRequestsQueue.add(Pair.of(produceHar, resultFuture));

        handleProducerRequestInternal();
    }

    protected void handleFindCoordinatorRequest(KafkaHeaderAndRequest findCoordinator,
                                                CompletableFuture<AbstractResponse> resultFuture) {
        checkArgument(findCoordinator.getRequest() instanceof FindCoordinatorRequest);
        FindCoordinatorRequest request = (FindCoordinatorRequest) findCoordinator.getRequest();

        if (FindCoordinatorRequest.CoordinatorType.forId(request.data().keyType()) == FindCoordinatorRequest.CoordinatorType.GROUP) {
            int partition = groupCoordinator.partitionFor(request.data().key());
            String pulsarTopicName = groupCoordinator.getTopicPartitionName(partition);

            findBroker(TopicName.get(pulsarTopicName))
                .whenComplete((node, t) -> {
                    if (t != null || node == null) {
                        log.error("[{}] Request {}: Error while find coordinator, .",
                            ctx.channel(), findCoordinator.getHeader(), t);

                        AbstractResponse response = FindCoordinatorResponse.prepareResponse(Errors.LEADER_NOT_AVAILABLE, Node.noNode());
                        resultFuture.complete(response);
                        return;
                    }

                    if (log.isDebugEnabled()) {
                        log.debug("[{}] Found node {} as coordinator for key {} partition {}.",
                            ctx.channel(), node.leader(), request.data().key(), partition);
                    }

                    FindCoordinatorResponseData findCoordinatorResponseData = new FindCoordinatorResponseData();
                    findCoordinatorResponseData.setErrorCode(Errors.NONE.code());
                    findCoordinatorResponseData.setHost(node.leader().host());
                    findCoordinatorResponseData.setNodeId(node.leader().id());
                    findCoordinatorResponseData.setPort(node.leader().port());
                    AbstractResponse response = new FindCoordinatorResponse(findCoordinatorResponseData);
                    resultFuture.complete(response);
                });
        } else {
            throw new NotImplementedException("FindCoordinatorRequest not support TRANSACTION type "
                + request.data().keyType());
        }
    }

    protected void handleOffsetFetchRequest(KafkaHeaderAndRequest offsetFetch,
                                            CompletableFuture<AbstractResponse> resultFuture) {
        checkArgument(offsetFetch.getRequest() instanceof OffsetFetchRequest);
        OffsetFetchRequest request = (OffsetFetchRequest) offsetFetch.getRequest();
        checkState(groupCoordinator != null,
            "Group Coordinator not started");

        KeyValue<Errors, Map<TopicPartition, OffsetFetchResponse.PartitionData>> keyValue =
            groupCoordinator.handleFetchOffsets(
                request.groupId(),
                Optional.of(request.partitions())
            );

        resultFuture.complete(new OffsetFetchResponse(keyValue.getKey(), keyValue.getValue()));
    }

    private CompletableFuture<ListOffsetResponse.PartitionData>
    fetchOffsetForTimestamp(CompletableFuture<PersistentTopic> persistentTopic, Long timestamp, boolean legacyMode) {
        CompletableFuture<ListOffsetResponse.PartitionData> partitionData = new CompletableFuture<>();

        persistentTopic.whenComplete((perTopic, t) -> {
            if (t != null || perTopic == null) {
                log.error("Failed while get persistentTopic topic: {} ts: {}. ",
                    perTopic == null ? "null" : perTopic.getName(), timestamp, t);

                partitionData.complete(new ListOffsetResponse.PartitionData(
                    Errors.LEADER_NOT_AVAILABLE,
                    ListOffsetResponse.UNKNOWN_TIMESTAMP,
                    ListOffsetResponse.UNKNOWN_OFFSET,
                    Optional.empty()));
                return;
            }

            ManagedLedgerImpl managedLedger = (ManagedLedgerImpl) perTopic.getManagedLedger();
            if (timestamp == ListOffsetRequest.LATEST_TIMESTAMP) {
                PositionImpl position = (PositionImpl) managedLedger.getLastConfirmedEntry();
                if (log.isDebugEnabled()) {
                    log.debug("Get latest position for topic {} time {}. result: {}",
                        perTopic.getName(), timestamp, position);
                }

                // no entry in ledger, then entry id could be -1
                long entryId = position.getEntryId();

                if (legacyMode) {
                    partitionData.complete(new ListOffsetResponse.PartitionData(
                        Errors.NONE,
                        Collections.singletonList(MessageIdUtils
                            .getOffset(position.getLedgerId(), entryId == -1 ? 0 : entryId))));

                } else {
                    partitionData.complete(new ListOffsetResponse.PartitionData(
                        Errors.NONE,
                        DEFAULT_TIMESTAMP,
                        MessageIdUtils
                            .getOffset(position.getLedgerId(), entryId == -1 ? 0 : entryId),
                        Optional.empty()));
                }

            } else if (timestamp == ListOffsetRequest.EARLIEST_TIMESTAMP) {
                PositionImpl position = OffsetFinder.getFirstValidPosition(managedLedger);

                if (log.isDebugEnabled()) {
                    log.debug("Get earliest position for topic {} time {}. result: {}",
                        perTopic.getName(), timestamp, position);
                }

                if (legacyMode) {
                    partitionData.complete(new ListOffsetResponse.PartitionData(
                        Errors.NONE,
                        Collections.singletonList(MessageIdUtils.getOffset(position.getLedgerId(),
                            position.getEntryId()))));
                } else {
                    partitionData.complete(new ListOffsetResponse.PartitionData(
                        Errors.NONE,
                        DEFAULT_TIMESTAMP,
                        MessageIdUtils.getOffset(position.getLedgerId(), position.getEntryId()),
                        Optional.empty()));
                }

            } else {
                // find with real wanted timestamp
                OffsetFinder offsetFinder = new OffsetFinder(managedLedger);

                offsetFinder.findMessages(timestamp, new AsyncCallbacks.FindEntryCallback() {
                    @Override
                    public void findEntryComplete(Position position, Object ctx) {
                        PositionImpl finalPosition;
                        if (position == null) {
                            finalPosition = OffsetFinder.getFirstValidPosition(managedLedger);
                            if (finalPosition == null) {
                                log.warn("Unable to find position for topic {} time {}. get NULL position",
                                    perTopic.getName(), timestamp);

                                if (legacyMode) {
                                    partitionData.complete(new ListOffsetResponse
                                        .PartitionData(
                                        Errors.UNKNOWN_SERVER_ERROR,
                                        emptyList()));
                                } else {
                                    partitionData.complete(new ListOffsetResponse
                                        .PartitionData(
                                        Errors.UNKNOWN_SERVER_ERROR,
                                        ListOffsetResponse.UNKNOWN_TIMESTAMP,
                                        ListOffsetResponse.UNKNOWN_OFFSET,
                                        Optional.empty()));
                                }
                                return;
                            }
                        } else {
                            finalPosition = (PositionImpl) position;
                        }

                        if (log.isDebugEnabled()) {
                            log.debug("Find position for topic {} time {}. position: {}",
                                perTopic.getName(), timestamp, finalPosition);
                        }
                        if (legacyMode) {
                            partitionData.complete(new ListOffsetResponse.PartitionData(
                                Errors.NONE,
                                Collections.singletonList(
                                    MessageIdUtils.getOffset(
                                        finalPosition.getLedgerId(), finalPosition.getEntryId()))));
                        } else {
                            partitionData.complete(new ListOffsetResponse.PartitionData(
                                Errors.NONE,
                                DEFAULT_TIMESTAMP,
                                MessageIdUtils.getOffset(finalPosition.getLedgerId(), finalPosition.getEntryId()),
                                Optional.empty()));
                        }
                    }

                    @Override
                    public void findEntryFailed(ManagedLedgerException exception,
                                                Optional<Position> position, Object ctx) {
                        log.warn("Unable to find position for topic {} time {}. Exception:",
                            perTopic.getName(), timestamp, exception);
                        if (legacyMode) {
                            partitionData.complete(new ListOffsetResponse
                                .PartitionData(
                                Errors.UNKNOWN_SERVER_ERROR,
                                emptyList()));
                        } else {
                            partitionData.complete(new ListOffsetResponse
                                .PartitionData(
                                Errors.UNKNOWN_SERVER_ERROR,
                                ListOffsetResponse.UNKNOWN_TIMESTAMP,
                                ListOffsetResponse.UNKNOWN_OFFSET,
                                Optional.empty()));
                        }
                        return;
                    }
                });
            }
        });

        return partitionData;
    }

    private void handleListOffsetRequestV1AndAbove(KafkaHeaderAndRequest listOffset,
                                                   CompletableFuture<AbstractResponse> resultFuture) {
        ListOffsetRequest request = (ListOffsetRequest) listOffset.getRequest();

        Map<TopicPartition, CompletableFuture<ListOffsetResponse.PartitionData>> responseData = Maps.newHashMap();

        request.partitionTimestamps().forEach((topic, value) -> {
            TopicName pulsarTopic = pulsarTopicName(topic, namespace);
            Long times = value.timestamp;
            CompletableFuture<ListOffsetResponse.PartitionData> partitionData;

            CompletableFuture<PersistentTopic> persistentTopic = topicManager.getTopic(pulsarTopic.toString());
            partitionData = fetchOffsetForTimestamp(persistentTopic, times, false);

            responseData.put(topic, partitionData);
        });

        CompletableFuture
            .allOf(responseData.values().toArray(new CompletableFuture<?>[0]))
            .whenComplete((ignore, ex) -> {
                ListOffsetResponse response =
                    new ListOffsetResponse(CoreUtils.mapValue(responseData, CompletableFuture::join));

                resultFuture.complete(response);
            });
    }

    // Some info can be found here
    // https://cfchou.github.io/blog/2015/04/23/a-closer-look-at-kafka-offsetrequest/ through web.archive.org
    private void handleListOffsetRequestV0(KafkaHeaderAndRequest listOffset,
                                           CompletableFuture<AbstractResponse> resultFuture) {
        ListOffsetRequest request = (ListOffsetRequest) listOffset.getRequest();

        Map<TopicPartition, CompletableFuture<ListOffsetResponse.PartitionData>> responseData = Maps.newHashMap();

        // in v0, the iterator is offsetData,
        // in v1, the iterator is partitionTimestamps,
        log.warn("received a v0 listOffset: {}", request.toString(true));
        request.partitionTimestamps().forEach((topic, value) -> {
            TopicName pulsarTopic = pulsarTopicName(topic, namespace);
            Long times = value.timestamp;
            CompletableFuture<ListOffsetResponse.PartitionData> partitionData;

            // num_num_offsets > 1 is not handled for now, returning an error
            if (value.maxNumOffsets > 1) {
                log.warn("request is asking for multiples offsets for {}, not supported for now",
                    pulsarTopic.toString());
                partitionData = new CompletableFuture<>();
                partitionData.complete(new ListOffsetResponse
                    .PartitionData(
                    Errors.UNKNOWN_SERVER_ERROR,
                    Collections.singletonList(ListOffsetResponse.UNKNOWN_OFFSET)));
            }

            CompletableFuture<PersistentTopic> persistentTopic = topicManager.getTopic(pulsarTopic.toString());
            partitionData = fetchOffsetForTimestamp(persistentTopic, times, true);

            responseData.put(topic, partitionData);
        });

        CompletableFuture
            .allOf(responseData.values().toArray(new CompletableFuture<?>[0]))
            .whenComplete((ignore, ex) -> {
                ListOffsetResponse response =
                    new ListOffsetResponse(CoreUtils.mapValue(responseData, future -> future.join()));

                resultFuture.complete(response);
            });
    }

    // get offset from underline managedLedger
    protected void handleListOffsetRequest(KafkaHeaderAndRequest listOffset,
                                           CompletableFuture<AbstractResponse> resultFuture) {
        checkArgument(listOffset.getRequest() instanceof ListOffsetRequest);
        // the only difference between v0 and v1 is the `max_num_offsets => INT32`
        // v0 is required because it is used by librdkafka
        if (listOffset.getHeader().apiVersion() == 0) {
            handleListOffsetRequestV0(listOffset, resultFuture);
        } else {
            handleListOffsetRequestV1AndAbove(listOffset, resultFuture);
        }
    }

    // For non exist topics handleOffsetCommitRequest return UNKNOWN_TOPIC_OR_PARTITION
    private Map<TopicPartition, Errors> nonExistingTopicErrors(OffsetCommitRequest request) {
        // TODO: in Kafka Metadata cache, all topics in the cluster is included, we should support it?
        //       we could get all the topic info by listTopic?
        //      https://github.com/streamnative/kop/issues/51
        return Maps.newHashMap();
//        return request.offsetData().entrySet().stream()
//                .filter(entry ->
//                    // filter not exist topics
//                    !topicManager.topicExists(pulsarTopicName(entry.getKey(), namespace).toString()))
//                .collect(Collectors.toMap(
//                    e -> e.getKey(),
//                    e -> Errors.UNKNOWN_TOPIC_OR_PARTITION
//                ));
    }

    protected void handleOffsetCommitRequest(KafkaHeaderAndRequest offsetCommit,
                                             CompletableFuture<AbstractResponse> resultFuture) {
        checkArgument(offsetCommit.getRequest() instanceof OffsetCommitRequest);
        checkState(groupCoordinator != null,
            "Group Coordinator not started");

        OffsetCommitRequest request = (OffsetCommitRequest) offsetCommit.getRequest();

        Map<TopicPartition, Errors> nonExistingTopic = nonExistingTopicErrors(request);

        groupCoordinator.handleCommitOffsets(
            request.data().groupId(),
            request.data().memberId(),
            request.data().generationId(),
            request.offsets().entrySet().stream()
                .filter(entry -> !nonExistingTopic.containsKey(entry.getKey()))
                .collect(
                    toMap(
                        Map.Entry::getKey,
                        entry -> OffsetAndMetadata.apply(entry.getValue())))
        ).thenAccept(offsetCommitResult -> {
            offsetCommitResult.putAll(nonExistingTopic);
            OffsetCommitResponse response = new OffsetCommitResponse(offsetCommitResult);
            resultFuture.complete(response);
        });
    }

    protected void handleFetchRequest(KafkaHeaderAndRequest fetch,
                                      CompletableFuture<AbstractResponse> resultFuture) {
        checkArgument(fetch.getRequest() instanceof FetchRequest);
        FetchRequest request = (FetchRequest) fetch.getRequest();

        if (log.isDebugEnabled()) {
            log.debug("[{}] Request {} Fetch request. Size: {}. Each item: ",
                ctx.channel(), fetch.getHeader(), request.fetchData().size());

            request.fetchData().forEach((topic, data) -> {
                log.debug("  Fetch request topic:{} data:{}.",
                    topic, data.toString());
            });
        }

        MessageFetchContext fetchContext = MessageFetchContext.get(this, fetch);
        fetchContext.handleFetch(resultFuture);
    }

    protected void handleJoinGroupRequest(KafkaHeaderAndRequest joinGroup,
                                          CompletableFuture<AbstractResponse> resultFuture) {
        checkArgument(joinGroup.getRequest() instanceof JoinGroupRequest);
        checkState(groupCoordinator != null,
            "Group Coordinator not started");

        JoinGroupRequest request = (JoinGroupRequest) joinGroup.getRequest();

        Map<String, byte[]> protocols = new HashMap<>();
        request.data().protocols()
            .forEach(protocol -> protocols.put(protocol.name(), protocol.metadata()));
        groupCoordinator.handleJoinGroup(
            request.data().groupId(),
            request.data().memberId(),
            joinGroup.getHeader().clientId(),
            joinGroup.getClientHost(),
            request.data().rebalanceTimeoutMs(),
            request.data().sessionTimeoutMs(),
            request.data().protocolType(),
            protocols
        ).thenAccept(joinGroupResult -> {
            List<JoinGroupResponseData.JoinGroupResponseMember> joinGroupResponseMembers = joinGroupResult.getMembers().entrySet().stream()
                .map(entry -> {
                    JoinGroupResponseData.JoinGroupResponseMember member = new JoinGroupResponseData.JoinGroupResponseMember();
                    member.setMemberId(entry.getKey());
                    member.setMetadata(entry.getValue());
                    return member;
                })
                .collect(toList());
            JoinGroupResponseData joinGroupResponseData = new JoinGroupResponseData();
            joinGroupResponseData.setErrorCode(joinGroupResult.getError().code());
            joinGroupResponseData.setGenerationId(joinGroupResult.getGenerationId());
            joinGroupResponseData.setProtocolName(joinGroupResult.getSubProtocol());
            joinGroupResponseData.setMemberId(joinGroupResult.getMemberId());
            joinGroupResponseData.setLeader(joinGroupResult.getLeaderId());
            joinGroupResponseData.setMembers(joinGroupResponseMembers);
            JoinGroupResponse response = new JoinGroupResponse(joinGroupResponseData);

            if (log.isTraceEnabled()) {
                log.trace("Sending join group response {} for correlation id {} to client {}.",
                    response, joinGroup.getHeader().correlationId(), joinGroup.getHeader().clientId());
            }

            resultFuture.complete(response);
        });
    }

    protected void handleSyncGroupRequest(KafkaHeaderAndRequest syncGroup,
                                          CompletableFuture<AbstractResponse> resultFuture) {
        checkArgument(syncGroup.getRequest() instanceof SyncGroupRequest);
        SyncGroupRequest request = (SyncGroupRequest) syncGroup.getRequest();

        Map<String, byte[]> assignments = request.data.assignments().stream()
            .collect(toMap(SyncGroupRequestData.SyncGroupRequestAssignment::memberId,
                SyncGroupRequestData.SyncGroupRequestAssignment::assignment));
        groupCoordinator.handleSyncGroup(
            request.data.groupId(),
            request.data.generationId(),
            request.data.memberId(),
            assignments
        ).thenAccept(syncGroupResult -> {
            SyncGroupResponseData syncGroupResponseData = new SyncGroupResponseData();
            syncGroupResponseData.setAssignment(syncGroupResult.getValue());
            syncGroupResponseData.setErrorCode(syncGroupResult.getKey().code());
            SyncGroupResponse response = new SyncGroupResponse(syncGroupResponseData);
            resultFuture.complete(response);
        });
    }

    protected void handleHeartbeatRequest(KafkaHeaderAndRequest heartbeat,
                                          CompletableFuture<AbstractResponse> resultFuture) {
        checkArgument(heartbeat.getRequest() instanceof HeartbeatRequest);
        HeartbeatRequest request = (HeartbeatRequest) heartbeat.getRequest();

        // let the coordinator to handle heartbeat
        groupCoordinator.handleHeartbeat(
            request.data.groupId(),
            request.data.memberId(),
            request.data.generationId()
        ).thenAccept(errors -> {
            HeartbeatResponseData heartbeatRequestData = new HeartbeatResponseData();
            heartbeatRequestData.setErrorCode(errors.code());
            HeartbeatResponse response = new HeartbeatResponse(heartbeatRequestData);
            if (log.isTraceEnabled()) {
                log.trace("Sending heartbeat response {} for correlation id {} to client {}.",
                    response, heartbeat.getHeader().correlationId(), heartbeat.getHeader().clientId());
            }

            resultFuture.complete(response);
        });
    }

    @Override
    protected void handleLeaveGroupRequest(KafkaHeaderAndRequest leaveGroup,
                                           CompletableFuture<AbstractResponse> resultFuture) {
        checkArgument(leaveGroup.getRequest() instanceof LeaveGroupRequest);
        LeaveGroupRequest request = (LeaveGroupRequest) leaveGroup.getRequest();

        // let the coordinator to handle heartbeat
        groupCoordinator.handleLeaveGroup(
            request.data().groupId(),
            request.data().memberId()
        ).thenAccept(errors -> {
            LeaveGroupResponseData leaveGroupResponseData = new LeaveGroupResponseData();
            leaveGroupResponseData.setErrorCode(errors.code());
            LeaveGroupResponse response = new LeaveGroupResponse(leaveGroupResponseData);
            resultFuture.complete(response);
        });
    }

    @Override
    protected void handleDescribeGroupRequest(KafkaHeaderAndRequest describeGroup,
                                              CompletableFuture<AbstractResponse> resultFuture) {
        checkArgument(describeGroup.getRequest() instanceof DescribeGroupsRequest);
        DescribeGroupsRequest request = (DescribeGroupsRequest) describeGroup.getRequest();

        // let the coordinator to handle heartbeat
        List<DescribeGroupsResponseData.DescribedGroup> groups = request.data().groups().stream()
            .map(groupId -> {
                KeyValue<Errors, GroupSummary> describeResult = groupCoordinator
                    .handleDescribeGroup(groupId);
                GroupSummary summary = describeResult.getValue();
                List<DescribeGroupsResponseData.DescribedGroupMember> members = summary.members().stream()
                    .map(memberSummary -> {
                        DescribeGroupsResponseData.DescribedGroupMember member = new DescribeGroupsResponseData.DescribedGroupMember();
                        member.setMemberId(memberSummary.memberId());
                        member.setClientId(memberSummary.clientId());
                        member.setClientHost(memberSummary.clientHost());
                        member.setMemberMetadata(member.memberMetadata());
                        member.setMemberAssignment(memberSummary.assignment());

                        return member;
                    })
                    .collect(toList());
                DescribeGroupsResponseData.DescribedGroup describedGroup = new DescribeGroupsResponseData.DescribedGroup();
                describedGroup.setGroupId(groupId);
                describedGroup.setErrorCode(describeResult.getKey().code());
                describedGroup.setMembers(members);
                describedGroup.setProtocolType(summary.protocolType());
                describedGroup.setProtocolData(summary.protocol());
                describedGroup.setGroupState(summary.state());
                return describedGroup;
            })
            .collect(toList());

        DescribeGroupsResponseData describeGroupsResponseData = new DescribeGroupsResponseData();
        describeGroupsResponseData.setGroups(groups);
        DescribeGroupsResponse response = new DescribeGroupsResponse(describeGroupsResponseData);
        resultFuture.complete(response);
    }

    @Override
    protected void handleListGroupsRequest(KafkaHeaderAndRequest listGroups,
                                           CompletableFuture<AbstractResponse> resultFuture) {
        checkArgument(listGroups.getRequest() instanceof ListGroupsRequest);
        KeyValue<Errors, List<GroupOverview>> listResult = groupCoordinator.handleListGroups();
        List<ListGroupsResponseData.ListedGroup> listedGroups = listResult.getValue().stream()
            .map(groupOverview -> {
                ListGroupsResponseData.ListedGroup listedGroup = new ListGroupsResponseData.ListedGroup();
                listedGroup.setGroupId(groupOverview.groupId());
                listedGroup.setProtocolType(groupOverview.protocolType());
                return listedGroup;
            })
            .collect(toList());

        ListGroupsResponseData listGroupsResponseData = new ListGroupsResponseData();
        listGroupsResponseData.setErrorCode(listResult.getKey().code());
        listGroupsResponseData.setGroups(listedGroups);
        ListGroupsResponse response = new ListGroupsResponse(listGroupsResponseData);
        resultFuture.complete(response);
    }

    @Override
    protected void handleDeleteGroupsRequest(KafkaHeaderAndRequest deleteGroups,
                                             CompletableFuture<AbstractResponse> resultFuture) {
        checkArgument(deleteGroups.getRequest() instanceof DeleteGroupsRequest);
        DeleteGroupsRequest request = (DeleteGroupsRequest) deleteGroups.getRequest();

        Map<String, Errors> deleteResult = groupCoordinator.handleDeleteGroups(new HashSet<>(request.data.groupsNames()));
        DeleteGroupsResponseData deleteGroupsRequestData = new DeleteGroupsResponseData();
        DeleteGroupsResponseData.DeletableGroupResultCollection deletableGroupResults = new DeleteGroupsResponseData.DeletableGroupResultCollection();
        List<DeleteGroupsResponseData.DeletableGroupResult> deletableGroupResultList = deleteResult.entrySet().stream()
            .map(entry -> {
                DeleteGroupsResponseData.DeletableGroupResult result = new DeleteGroupsResponseData.DeletableGroupResult();
                result.setErrorCode(entry.getValue().code());
                result.setGroupId(entry.getKey());
                return result;
            })
            .collect(toList());
        deletableGroupResults.addAll(deletableGroupResultList);
        deleteGroupsRequestData.setResults(deletableGroupResults);
        DeleteGroupsResponse response = new DeleteGroupsResponse(deleteGroupsRequestData);
        resultFuture.complete(response);
    }

    @Override
    protected void handleSaslAuthenticate(KafkaHeaderAndRequest saslAuthenticate,
                                          CompletableFuture<AbstractResponse> resultFuture) {
        checkArgument(saslAuthenticate.getRequest() instanceof SaslAuthenticateRequest);
        SaslAuthenticateRequest request = (SaslAuthenticateRequest) saslAuthenticate.getRequest();

        SaslAuth saslAuth;
        try {
            saslAuth = SaslUtils.parseSaslAuthBytes(request.data().authBytes());

            namespace = NamespaceName.get(saslAuth.getUsername());
            AuthData authData = AuthData.of(saslAuth.getAuthData().getBytes(UTF_8));

            AuthenticationService authenticationService = getPulsarService()
                .getBrokerService().getAuthenticationService();
            AuthenticationProvider authenticationProvider = authenticationService
                .getAuthenticationProvider(saslAuth.getAuthMethod());
            if (null == authenticationProvider) {
                throw new PulsarClientException.AuthenticationException("cannot find provider "
                    + saslAuth.getAuthMethod());
            }

            authState = authenticationProvider.newAuthState(authData, remoteAddress, null);
            authRole = authState.getAuthRole();

            Map<String, Set<AuthAction>> permissions = getAdmin()
                .namespaces().getPermissions(saslAuth.getUsername());
            if (!permissions.containsKey(authRole)) {
                throw new AuthorizationException("Role: " + authRole + " Not allowed on this namespace");
            }

            log.debug("successfully authenticate user " + authRole);

            // TODO: what should be answered?
            SaslAuthenticateResponseData saslAuthenticateResponseData = new SaslAuthenticateResponseData();
            saslAuthenticateResponseData.setAuthBytes(request.data().authBytes());
            saslAuthenticateResponseData.setErrorCode(Errors.NONE.code());
            saslAuthenticateResponseData.setErrorMessage("");
            SaslAuthenticateResponse response = new SaslAuthenticateResponse(saslAuthenticateResponseData);
            resultFuture.complete(response);

        } catch (IOException | AuthenticationException | PulsarAdminException e) {
            SaslAuthenticateResponseData saslAuthenticateResponseData = new SaslAuthenticateResponseData();
            saslAuthenticateResponseData.setAuthBytes(request.data().authBytes());
            saslAuthenticateResponseData.setErrorCode(Errors.SASL_AUTHENTICATION_FAILED.code());
            saslAuthenticateResponseData.setErrorMessage(e.getMessage());
            SaslAuthenticateResponse response = new SaslAuthenticateResponse(saslAuthenticateResponseData);
            resultFuture.complete(response);
        }
    }

    @Override
    protected void handleSaslHandshake(KafkaHeaderAndRequest saslHandshake,
                                       CompletableFuture<AbstractResponse> resultFuture) {
        checkArgument(saslHandshake.getRequest() instanceof SaslHandshakeRequest);
        SaslHandshakeRequest request = (SaslHandshakeRequest) saslHandshake.getRequest();

        SaslHandshakeResponse response = checkSaslMechanism(request.data().mechanism());
        resultFuture.complete(response);
    }

    private SaslHandshakeResponse checkSaslMechanism(String mechanism) {
        if (getKafkaConfig().getSaslAllowedMechanisms().contains(mechanism)) {
            SaslHandshakeResponseData saslHandshakeResponseData = new SaslHandshakeResponseData();
            saslHandshakeResponseData.setErrorCode(Errors.NONE.code());
            saslHandshakeResponseData.setMechanisms(new ArrayList<>(getKafkaConfig().getSaslAllowedMechanisms()));
            return new SaslHandshakeResponse(saslHandshakeResponseData);
        }
        SaslHandshakeResponseData saslHandshakeResponseData = new SaslHandshakeResponseData();
        saslHandshakeResponseData.setErrorCode(Errors.UNSUPPORTED_SASL_MECHANISM.code());
        saslHandshakeResponseData.setMechanisms(new ArrayList<>());
        return new SaslHandshakeResponse(saslHandshakeResponseData);
    }

    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Caught error in handler, closing channel", cause);
        ctx.close();
    }

    private CompletableFuture<Optional<String>>
    getProtocolDataToAdvertise(InetSocketAddress pulsarAddress,
                               TopicName topic) {

        CompletableFuture<Optional<String>> returnFuture = new CompletableFuture<>();

        if (pulsarAddress == null) {
            log.error("[{}] failed get pulsar address, returned null.", topic.toString());

            // getTopicBroker returns null. topic should be removed from LookupCache.
            topicManager.removeLookupCache(topic.toString());

            returnFuture.complete(Optional.empty());
            return returnFuture;
        }

        if (log.isDebugEnabled()) {
            log.debug("Found broker for topic {} puslarAddress: {}",
                topic, pulsarAddress);
        }

        // advertised data is write in  /loadbalance/brokers/advertisedAddress:webServicePort
        // here we get the broker url, need to find related webServiceUrl.
        ZooKeeperCache zkCache = pulsarService.getLocalZkCache();
        zkCache.getChildrenAsync(LoadManager.LOADBALANCE_BROKERS_ROOT, zkCache)
            .whenComplete((set, throwable) -> {
                if (throwable != null) {
                    log.error("Error in getChildrenAsync(zk://loadbalance) for {}", pulsarAddress, throwable);
                    returnFuture.complete(Optional.empty());
                    return;
                }

                String hostAndPort = pulsarAddress.getHostName() + ":" + pulsarAddress.getPort();
                List<String> matchBrokers = Lists.newArrayList();
                // match host part of url
                for (String activeBroker : set) {
                    if (activeBroker.startsWith(pulsarAddress.getHostName() + ":")) {
                        matchBrokers.add(activeBroker);
                    }
                }

                if (matchBrokers.isEmpty()) {
                    log.error("No node for broker {} under zk://loadbalance", pulsarAddress);
                    returnFuture.complete(Optional.empty());
                    return;
                }

                // Get a list of ServiceLookupData for each matchBroker.
                List<CompletableFuture<Optional<ServiceLookupData>>> list = matchBrokers.stream()
                    .map(matchBroker ->
                        zkCache.getDataAsync(
                            String.format("%s/%s", LoadManager.LOADBALANCE_BROKERS_ROOT, matchBroker),
                            (Deserializer<ServiceLookupData>)
                                pulsarService.getLoadManager().get().getLoadReportDeserializer()))
                    .collect(toList());

                FutureUtil.waitForAll(list)
                    .whenComplete((ignore, th) -> {
                            if (th != null) {
                                log.error("Error in getDataAsync() for {}", pulsarAddress, th);
                                returnFuture.complete(Optional.empty());
                                return;
                            }

                            try {
                                for (CompletableFuture<Optional<ServiceLookupData>> lookupData : list) {
                                    ServiceLookupData data = lookupData.get().get();
                                    if (log.isDebugEnabled()) {
                                        log.debug("Handle getProtocolDataToAdvertise for {}, pulsarUrl: {}, "
                                                + "pulsarUrlTls: {}, webUrl: {}, webUrlTls: {} kafka: {}",
                                            topic, data.getPulsarServiceUrl(), data.getPulsarServiceUrlTls(),
                                            data.getWebServiceUrl(), data.getWebServiceUrlTls(),
                                            data.getProtocol(KafkaProtocolHandler.PROTOCOL_NAME));
                                    }

                                    if (lookupDataContainsAddress(data, hostAndPort)) {
                                        returnFuture.complete(data.getProtocol(KafkaProtocolHandler.PROTOCOL_NAME));
                                        return;
                                    }
                                }
                            } catch (Exception e) {
                                log.error("Error in {} lookupFuture get: ", pulsarAddress, e);
                                returnFuture.complete(Optional.empty());
                                return;
                            }

                            // no matching lookup data in all matchBrokers.
                            log.error("Not able to search {} in all child of zk://loadbalance", pulsarAddress);
                            returnFuture.complete(Optional.empty());
                        }
                    );
            });
        return returnFuture;
    }

    private boolean isOffsetTopic(String topic) {
        String offsetsTopic = kafkaConfig.getKafkaMetadataTenant() + "/"
            + kafkaConfig.getKafkaMetadataNamespace()
            + "/" + Topic.GROUP_METADATA_TOPIC_NAME;

        return topic.contains(offsetsTopic);
    }

    private CompletableFuture<PartitionMetadata> findBroker(TopicName topic) {
        if (log.isDebugEnabled()) {
            log.debug("[{}] Handle Lookup for {}", ctx.channel(), topic);
        }
        CompletableFuture<PartitionMetadata> returnFuture = new CompletableFuture<>();

        topicManager.getTopicBroker(topic.toString())
            .thenCompose(pair -> getProtocolDataToAdvertise(pair, topic))
            .whenComplete((stringOptional, throwable) -> {
                if (!stringOptional.isPresent() || throwable != null) {
                    log.error("Not get advertise data for Kafka topic:{}. throwable",
                        topic, throwable);
                    returnFuture.complete(null);
                    return;
                }

                String listeners = stringOptional.get();
                String kopBrokerUrl = getKopBrokerUrl(listeners, tlsEnabled);
                URI kopUri;
                try {
                    kopUri = new URI(kopBrokerUrl);
                } catch (URISyntaxException e) {
                    log.error("[{}] findBroker for topic {}: Failed to translate URI {}. exception:",
                        ctx.channel(), topic.toString(), kopBrokerUrl, e);
                    returnFuture.complete(null);
                    return;
                }

                Node node = newNode(new InetSocketAddress(
                    kopUri.getHost(),
                    kopUri.getPort()));

                if (log.isDebugEnabled()) {
                    log.debug("Found broker localListeners: {} for topicName: {}, "
                            + "localListeners: {}, found Listeners: {}",
                        listeners, topic, localListeners, listeners);
                }

                // here we found topic broker: broker2, but this is in broker1,
                // how to clean the lookup cache?
                if (!localListeners.contains(kopBrokerUrl)) {
                    topicManager.removeLookupCache(topic.toString());
                }

                if (!topicManager.topicExists(topic.toString())
                    && localListeners.contains(kopBrokerUrl)) {
                    topicManager.getTopic(topic.toString()).whenComplete((persistentTopic, exception) -> {
                        if (exception != null || persistentTopic == null) {
                            log.warn("[{}] findBroker: Failed to getOrCreateTopic {}. broker:{}, exception:",
                                ctx.channel(), topic.toString(), kopBrokerUrl, exception);
                            returnFuture.complete(null);
                        } else {
                            if (log.isDebugEnabled()) {
                                log.debug("Add topic: {} into TopicManager while findBroker.",
                                    topic.toString());
                            }
                            returnFuture.complete(newPartitionMetadata(topic, node));
                        }
                    });
                } else {
                    returnFuture.complete(newPartitionMetadata(topic, node));
                }
            });
        return returnFuture;
    }

    static Node newNode(InetSocketAddress address) {
        if (log.isDebugEnabled()) {
            log.debug("Return Broker Node of {}. {}:{}", address, address.getHostString(), address.getPort());
        }
        return new Node(
            Murmur3_32Hash.getInstance().makeHash((address.getHostString() + address.getPort()).getBytes(UTF_8)),
            address.getHostString(),
            address.getPort());
    }

    Node newSelfNode() {
        String hostname = ServiceConfigurationUtils.getDefaultOrConfiguredAddress(
            kafkaConfig.getAdvertisedAddress());

        int port = tlsEnabled ? sslPort : plaintextPort;

        if (log.isDebugEnabled()) {
            log.debug("Return Broker Node of Self: {}:{}", hostname, port);
        }

        return new Node(
            Murmur3_32Hash.getInstance().makeHash((hostname + port).getBytes(UTF_8)),
            hostname,
            port);
    }

    MetadataResponseData.MetadataResponseBroker newSelfBroker() {
        String hostname = ServiceConfigurationUtils.getDefaultOrConfiguredAddress(
            kafkaConfig.getAdvertisedAddress());

        int port = tlsEnabled ? sslPort : plaintextPort;

        if (log.isDebugEnabled()) {
            log.debug("Return Broker Node of Self: {}:{}", hostname, port);
        }
        MetadataResponseData.MetadataResponseBroker metadataResponseBroker = new MetadataResponseData.MetadataResponseBroker();
        metadataResponseBroker.setHost(hostname);
        metadataResponseBroker.setPort(port);
        metadataResponseBroker.setNodeId(Murmur3_32Hash.getInstance().makeHash((hostname + port).getBytes(UTF_8)));
        return metadataResponseBroker;
    }


    static PartitionMetadata newPartitionMetadata(TopicName topicName, Node node) {
        int pulsarPartitionIndex = topicName.getPartitionIndex();
        int kafkaPartitionIndex = pulsarPartitionIndex == -1 ? 0 : pulsarPartitionIndex;

        if (log.isDebugEnabled()) {
            log.debug("Return PartitionMetadata node: {}, topicName: {}", node, topicName);
        }

        return new PartitionMetadata(
            Errors.NONE,
            kafkaPartitionIndex,
            node,                      // leader
            Optional.empty(),
            Lists.newArrayList(node),  // replicas
            Lists.newArrayList(node),  // isr
            emptyList()     // offline replicas
        );
    }

    static MetadataResponseData.MetadataResponsePartition newFailedMetadataResponsePartition(TopicName topicName) {
        int pulsarPartitionIndex = topicName.getPartitionIndex();
        int kafkaPartitionIndex = pulsarPartitionIndex == -1 ? 0 : pulsarPartitionIndex;

        log.warn("Failed find Broker metadata, create PartitionMetadata with NOT_LEADER_FOR_PARTITION");

        // most of this error happens when topic is in loading/unloading status,
        MetadataResponseData.MetadataResponsePartition metadataResponsePartition = new MetadataResponseData.MetadataResponsePartition();
        metadataResponsePartition.setErrorCode(NOT_LEADER_FOR_PARTITION.code());
        metadataResponsePartition.setPartitionIndex(kafkaPartitionIndex);
        return metadataResponsePartition;
    }

    static AbstractResponse failedResponse(KafkaHeaderAndRequest requestHar, Throwable e) {
        if (log.isDebugEnabled()) {
            log.debug("Request {} get failed response ", requestHar.getHeader().apiKey(), e);
        }
        return requestHar.getRequest().getErrorResponse(((Integer) THROTTLE_TIME_MS.defaultValue), e);
    }

    // whether a ServiceLookupData contains wanted address.
    static boolean lookupDataContainsAddress(ServiceLookupData data, String hostAndPort) {
        return (data.getPulsarServiceUrl() != null && data.getPulsarServiceUrl().contains(hostAndPort))
            || (data.getPulsarServiceUrlTls() != null && data.getPulsarServiceUrlTls().contains(hostAndPort))
            || (data.getWebServiceUrl() != null && data.getWebServiceUrl().contains(hostAndPort))
            || (data.getWebServiceUrlTls() != null && data.getWebServiceUrlTls().contains(hostAndPort));
    }
}
