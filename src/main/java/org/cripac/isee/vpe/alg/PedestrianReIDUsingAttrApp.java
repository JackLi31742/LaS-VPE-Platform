/***********************************************************************
 * This file is part of LaS-VPE Platform.
 *
 * LaS-VPE Platform is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LaS-VPE Platform is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LaS-VPE Platform.  If not, see <http://www.gnu.org/licenses/>.
 ************************************************************************/

package org.cripac.isee.vpe.alg;

import com.google.common.base.Optional;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.log4j.Level;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.cripac.isee.pedestrian.attr.Attributes;
import org.cripac.isee.pedestrian.reid.PedestrianInfo;
import org.cripac.isee.pedestrian.reid.PedestrianReIDer;
import org.cripac.isee.pedestrian.tracking.Tracklet;
import org.cripac.isee.vpe.common.DataType;
import org.cripac.isee.vpe.common.SparkStreamingApp;
import org.cripac.isee.vpe.common.Stream;
import org.cripac.isee.vpe.common.Topic;
import org.cripac.isee.vpe.ctrl.SystemPropertyCenter;
import org.cripac.isee.vpe.ctrl.TaskData;
import org.cripac.isee.vpe.ctrl.TopicManager;
import org.cripac.isee.vpe.debug.FakePedestrianReIDerWithAttr;
import org.cripac.isee.vpe.util.Singleton;
import org.cripac.isee.vpe.util.kafka.KafkaProducerFactory;
import org.cripac.isee.vpe.util.logging.Logger;
import org.cripac.isee.vpe.util.logging.SynthesizedLogger;
import org.cripac.isee.vpe.util.logging.SynthesizedLoggerFactory;
import scala.Tuple2;

import java.util.*;

import static org.cripac.isee.vpe.util.SerializationHelper.deserialize;
import static org.cripac.isee.vpe.util.SerializationHelper.serialize;
import static org.cripac.isee.vpe.util.kafka.KafkaHelper.sendWithLog;

/**
 * The PedestrianReIDApp class is a Spark Streaming application which performs
 * pedestrian re-identification with attributes.
 *
 * @author Ken Yu, CRIPAC, 2016
 */
public class PedestrianReIDUsingAttrApp extends SparkStreamingApp {
    /**
     * The NAME of this application.
     */
    public static final String APP_NAME = "pedestrian-reID-using-attr";

    private Stream reidStream;

    /**
     * Constructor of the application, configuring properties read from a
     * property center.
     *
     * @param propCenter A class saving all the properties this application may need.
     * @throws Exception
     */
    public PedestrianReIDUsingAttrApp(SystemPropertyCenter propCenter) throws Exception {
        reidStream = new ReIDStream(propCenter);
    }

    /**
     * @param args No options supported currently.
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        // Load system properties.
        SystemPropertyCenter propertyCenter;
        propertyCenter = new SystemPropertyCenter(args);

        // Start the pedestrian tracking application.
        SparkStreamingApp app = new PedestrianReIDUsingAttrApp(propertyCenter);
        TopicManager.checkTopics(propertyCenter);
        app.initialize(propertyCenter);
        app.start();
        app.awaitTermination();
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * SparkStreamingApp#getStreamContext()
     */
    @Override
    protected JavaStreamingContext getStreamContext() {
        // Create contexts.
        JavaSparkContext sparkContext = new JavaSparkContext(new SparkConf(true));
        sparkContext.setLocalProperty("spark.scheduler.pool", "vpe");
        JavaStreamingContext jsc = new JavaStreamingContext(sparkContext, Durations.seconds(2));

        reidStream.addToContext(jsc);

        return jsc;
    }

    /*
     * (non-Javadoc)
     *
     * @see SparkStreamingApp#getStreamInfo()
     */
    @Override
    public String getAppName() {
        return APP_NAME;
    }

    public static class ReIDStream extends Stream {

        public static final Info INFO =
                new Info("PedestrianReIDUsingAttr", DataType.IDRANK);

        /**
         * Topic to input pedestrian tracklets from Kafka.
         */
        public static final Topic TRACKLET_TOPIC = new Topic(
                "pedestrian-tracklet-for-reid-using-attr",
                DataType.TRACKLET, INFO);
        /**
         * Topic to input pedestrian attributes from Kafka.
         */
        public static final Topic ATTR_TOPIC = new Topic(
                "pedestrian-attr-for-reid-using-attr",
                DataType.ATTR, INFO);
        /**
         * Topic to input pedestrian track with attributes from Kafka.
         */
        public static final Topic TRACKLET_ATTR_TOPIC = new Topic(
                "pedestrian-track-attr-for-reid-using-attr",
                DataType.TRACKLET, INFO);

        /**
         * Kafka parameters for creating input streams pulling messages from Kafka
         * Brokers.
         */
        private Map<String, String> kafkaParams = new HashMap<>();
        /**
         * Topics for inputting tracklets. Each assigned a number of threads the Kafka
         * consumer should use.
         */
        private Map<String, Integer> trackTopicMap = new HashMap<>();
        /**
         * Topics for inputting attributes. Each assigned a number of threads the
         * Kafka consumer should use.
         */
        private Map<String, Integer> attrTopicMap = new HashMap<>();
        /**
         * Topics for inputting tracklets with attributes. Each assigned a number of
         * threads the Kafka consumer should use. partitions.
         */
        private Map<String, Integer> trackWithAttrTopicMap = new HashMap<>();

        /**
         * Duration for buffering results.
         */
        private int bufDuration;

        private Singleton<KafkaProducer<String, byte[]>> producerSingleton;
        private Singleton<PedestrianReIDer> reidSingleton;
        private Singleton<SynthesizedLogger> loggerSingleton;

        public ReIDStream(SystemPropertyCenter propCenter) throws Exception {
            trackTopicMap.put(TRACKLET_TOPIC.NAME, propCenter.kafkaNumPartitions);
            attrTopicMap.put(ATTR_TOPIC.NAME, propCenter.kafkaNumPartitions);
            trackWithAttrTopicMap.put(TRACKLET_ATTR_TOPIC.NAME, propCenter.kafkaNumPartitions);

            bufDuration = propCenter.bufDuration;

            // Common kafka settings.
            kafkaParams.put("group.id", INFO.NAME);
            kafkaParams.put("zookeeper.connect", propCenter.zkConn);
            // Determine where the stream starts (default: largest)
            kafkaParams.put("auto.offset.reset", "smallest");
            kafkaParams.put("metadata.broker.list", propCenter.kafkaBrokers);
            kafkaParams.put("fetch.message.max.bytes", "" + propCenter.kafkaFetchMsgMaxBytes);

            Properties producerProp = new Properties();
            producerProp.put("bootstrap.servers", propCenter.kafkaBrokers);
            producerProp.put("compression.codec", "1");
            producerProp.put("max.request.size", "10000000");
            producerProp.put("key.serializer",
                    "org.apache.kafka.common.serialization.StringSerializer");
            producerProp.put("value.serializer",
                    "org.apache.kafka.common.serialization.ByteArraySerializer");

            producerSingleton = new Singleton<>(
                    new KafkaProducerFactory<String, byte[]>(producerProp));
            reidSingleton = new Singleton<>(
                    () -> new FakePedestrianReIDerWithAttr());
            loggerSingleton = new Singleton<>(
                    new SynthesizedLoggerFactory(
                            INFO.NAME,
                            propCenter.verbose ? Level.DEBUG : Level.INFO,
                            propCenter.reportListenerAddr,
                            propCenter.reportListenerPort));
        }

        @Override
        public void addToContext(JavaStreamingContext jsc) {
            JavaPairDStream<String, TaskData> trackletDStream =
                    // Read track bytes in parallel from Kafka.
                    buildBytesDirectStream(jsc, kafkaParams, trackTopicMap)
                            // Recover track from the bytes
                            // and extract the IDRANK of the track.
                            .mapToPair(taskDataBytes -> {
                                TaskData taskData =
                                        (TaskData) deserialize(taskDataBytes._2());
                                loggerSingleton.getInst().info(
                                        "Received track: " + ((Tracklet) taskData.predecessorRes).id);
                                return new Tuple2<>(
                                        taskDataBytes._1() + ":" + ((Tracklet) taskData.predecessorRes).id,
                                        taskData);
                            });

            JavaPairDStream<String, TaskData> attrDStream =
                    // Read attribute bytes in parallel from Kafka.
                    buildBytesDirectStream(jsc, kafkaParams, attrTopicMap)
                            // Recover attributes from the bytes
                            // and extract the IDRANK of the track
                            // the attributes belong to.
                            .mapToPair(taskDataBytes -> {
                                TaskData taskData =
                                        (TaskData) deserialize(taskDataBytes._2());

                                if (!(taskData.predecessorRes instanceof Attributes)) {
                                    throw new ClassCastException(
                                            "Predecessor result is expected to be attributes,"
                                                    + " but received \""
                                                    + taskData.predecessorRes
                                                    + "\"!");
                                }

                                loggerSingleton.getInst().info(
                                        "Received " + taskDataBytes._1() + ": " + taskData);
                                return new Tuple2<>(taskDataBytes._1() + ":"
                                        + ((Attributes) taskData.predecessorRes).trackletID,
                                        taskData);
                            });

            // Join the track stream and attribute stream, tolerating failure.
            JavaPairDStream<String, Tuple2<Optional<TaskData>, Optional<TaskData>>> unsurelyJoinedDStream =
                    trackletDStream.fullOuterJoin(attrDStream);

            // Filter out instantly joined pairs.
            JavaPairDStream<String, Tuple2<TaskData, TaskData>> instantlyJoinedDStream =
                    unsurelyJoinedDStream
                            .filter(item ->
                                    new Boolean(item._2()._1().isPresent() && item._2()._2().isPresent()))
                            .mapValues(optPair
                                    -> new Tuple2<>(optPair._1().get(), optPair._2().get()));

            // Filter out tracklets that cannot find attributes to match.
            JavaPairDStream<String, TaskData> unjoinedTrackDStream =
                    unsurelyJoinedDStream
                            .filter(item ->
                                    new Boolean(item._2()._1().isPresent() && !item._2()._2().isPresent()))
                            .mapValues(optPair -> optPair._1().get());

            // Filter out attributes that cannot find tracklets to match.
            JavaPairDStream<String, TaskData> unjoinedAttrStream = unsurelyJoinedDStream
                    .filter(item ->
                            new Boolean(!item._2()._1().isPresent() && item._2()._2().isPresent()))
                    .mapValues(optPair -> optPair._2().get());

            JavaPairDStream<String, Tuple2<Optional<TaskData>, TaskData>> unsurelyJoinedAttrDStream =
                    unjoinedTrackDStream
                            .window(Durations.milliseconds(bufDuration))
                            .rightOuterJoin(unjoinedAttrStream);

            JavaPairDStream<String, Tuple2<TaskData, TaskData>> lateAttrJoinedDStream =
                    unsurelyJoinedAttrDStream
                            .filter(item -> new Boolean(item._2()._1().isPresent()))
                            .mapValues(item -> new Tuple2<>(item._1().get(), item._2()));

            JavaPairDStream<String, Tuple2<TaskData, TaskData>> lateTrackJoinedDStream =
                    unjoinedTrackDStream
                            .join(unsurelyJoinedAttrDStream
                                    .filter(item -> new Boolean(!item._2()._1().isPresent()))
                                    .mapValues(item -> item._2())
                                    .window(Durations.milliseconds(bufDuration)));

            // Union the three track and attribute streams and assemble
            // their TaskData.
            JavaPairDStream<String, TaskData> asmTrackletAttrDStream =
                    instantlyJoinedDStream.union(lateTrackJoinedDStream)
                            .union(lateAttrJoinedDStream)
                            .mapToPair(pack -> {
                                String taskID = pack._1().split(":")[0];
                                TaskData taskDataWithTrack = pack._2()._1();
                                TaskData taskDataWithAttr = pack._2()._2();
                                TaskData.ExecutionPlan asmPlan =
                                        TaskData.ExecutionPlan.combine(
                                                taskDataWithTrack.executionPlan,
                                                taskDataWithAttr.executionPlan);

                                TaskData asmTaskData = new TaskData(
                                        taskDataWithTrack.curNode,
                                        asmPlan,
                                        new PedestrianInfo(
                                                (Tracklet) taskDataWithTrack.predecessorRes,
                                                (Attributes) taskDataWithAttr.predecessorRes));
                                loggerSingleton.getInst().debug(
                                        "Assembled track and attr of " + pack._1());
                                return new Tuple2<>(taskID, asmTaskData);
                            });

            // Read track with attribute bytes in parallel from Kafka.
            // Recover attributes from the bytes and extract the IDRANK of the track the
            // attributes belong to.
            JavaPairDStream<String, TaskData> integralTrackletAttrDStream =
                    buildBytesDirectStream(jsc, kafkaParams, trackWithAttrTopicMap)
                            .mapValues(bytes -> (TaskData) deserialize(bytes));

            // Union the two track with attribute streams and perform ReID.
            integralTrackletAttrDStream.union(asmTrackletAttrDStream)
                    .foreachRDD(rdd -> {
                        rdd.foreach(taskWithTrackletAttr -> {
                            Logger logger = loggerSingleton.getInst();
                            String taskID = taskWithTrackletAttr._1();
                            TaskData taskData = taskWithTrackletAttr._2();
                            if (taskData.predecessorRes == null) {
                                logger.fatal("TaskData from " + taskData.predecessorInfo
                                        + " contains no result data!");
                                return;
                            }
                            if (!(taskData.predecessorRes instanceof PedestrianInfo)) {
                                logger.fatal("TaskData from " + taskData.predecessorInfo
                                        + " contains no result data!");
                                logger.fatal("Result sent by "
                                        + taskData.predecessorInfo
                                        + " is expected to be a PedestrianInfo,"
                                        + " but received \""
                                        + taskData.predecessorRes + "\"!");
                                return;
                            }
                            PedestrianInfo trackletWithAttr =
                                    (PedestrianInfo) taskData.predecessorRes;

                            // Perform ReID.
                            int[] idRank = reidSingleton.getInst().reid(trackletWithAttr);

                            // Prepare new task data with the pedestrian IDRANK.
                            taskData.predecessorRes = idRank;
                            // Get the IDs of successor nodes.
                            List<Topic> succTopics = taskData.curNode.getSuccessors();
                            // Mark the current node as executed.
                            taskData.curNode.markExecuted();
                            // Send to all the successor nodes.
                            for (Topic topic : succTopics) {
                                taskData.changeCurNode(topic);
                                sendWithLog(topic,
                                        taskID,
                                        serialize(taskData),
                                        producerSingleton.getInst(),
                                        logger);
                            }
                        });
                    });
        }
    }
}
