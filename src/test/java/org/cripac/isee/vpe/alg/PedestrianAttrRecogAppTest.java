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

import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.log4j.Level;
import org.cripac.isee.vpe.common.DataType;
import org.cripac.isee.vpe.common.Topic;
import org.cripac.isee.vpe.ctrl.SystemPropertyCenter;
import org.cripac.isee.vpe.ctrl.TaskData;
import org.cripac.isee.vpe.ctrl.TopicManager;
import org.cripac.isee.vpe.debug.FakePedestrianTracker;
import org.cripac.isee.vpe.util.logging.ConsoleLogger;
import org.junit.Before;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;

import static org.cripac.isee.vpe.util.SerializationHelper.deserialize;
import static org.cripac.isee.vpe.util.SerializationHelper.serialize;
import static org.cripac.isee.vpe.util.kafka.KafkaHelper.sendWithLog;

/**
 * This is a JUnit test for the DataManagingApp.
 * Different from usual JUnit tests, this test does not initiate a DataManagingApp.
 * The application should be run on YARN in advance.
 * This test only sends fake data messages to and receives results
 * from the already running application through Kafka.
 *
 * Created by ken.yu on 16-10-31.
 */
public class PedestrianAttrRecogAppTest {

    public static final Topic TEST_PED_ATTR_RECV_TOPIC
            = new Topic("test-ped-attr-recv", DataType.ATTR, null);

    private KafkaProducer<String, byte[]> producer;
    private KafkaConsumer<String, byte[]> consumer;
    private ConsoleLogger logger;

    public static void main(String[] args) {
        PedestrianAttrRecogAppTest test = new PedestrianAttrRecogAppTest();
        try {
            test.init(args);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        try {
            test.testAttrRecog();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Before
    public void init() throws Exception {
        init(new String[0]);
    }

    public void init(String[] args) throws SAXException, ParserConfigurationException, URISyntaxException {
        SystemPropertyCenter propCenter = new SystemPropertyCenter(args);

        TopicManager.checkTopics(propCenter);

        Properties producerProp = new Properties();
        producerProp.put("bootstrap.servers", propCenter.kafkaBrokers);
        producerProp.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producerProp.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        producer = new KafkaProducer<>(producerProp);
        logger = new ConsoleLogger(Level.DEBUG);

        Properties consumerProp = new Properties();
        consumerProp.put("bootstrap.servers", propCenter.kafkaBrokers);
        consumerProp.put("group.id", "test");
        consumerProp.put("enable.auto.commit", true);
        consumerProp.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        consumerProp.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        consumer = new KafkaConsumer<>(consumerProp);
        consumer.subscribe(Arrays.asList(TEST_PED_ATTR_RECV_TOPIC.NAME));
    }

//    @Test
    public void testAttrRecog() throws Exception {
        TaskData.ExecutionPlan plan = new TaskData.ExecutionPlan();
        TaskData.ExecutionPlan.Node recogNode =
                plan.addNode(PedestrianAttrRecogApp.RecogStream.INFO);
        plan.letNodeOutputTo(recogNode, TEST_PED_ATTR_RECV_TOPIC);

        // Send request (fake tracklet).
        TaskData trackletData = new TaskData(recogNode, plan,
                new FakePedestrianTracker().track(new byte[0]));
        sendWithLog(PedestrianAttrRecogApp.RecogStream.TRACKLET_TOPIC,
                UUID.randomUUID().toString(),
                serialize(trackletData),
                producer,
                logger);

        // Receive result (attributes).
        ConsumerRecords<String, byte[]> records = consumer.poll(0);
        records.forEach(rec -> {
            TaskData attrData = null;
            try {
                attrData = (TaskData) deserialize(rec.value());
                logger.info("<" + rec.topic() + ">\t" + rec.key() + "\t-\t" + attrData.predecessorRes);
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        });
    }
}
