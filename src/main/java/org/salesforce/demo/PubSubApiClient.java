package org.salesforce.demo;

import com.google.protobuf.ByteString;
import com.salesforce.eventbus.protobuf.*;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.grpc.stub.StreamObserver;
import io.grpc.stub.StreamObservers;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.EncoderFactory;
import org.salesforce.demo.auth.AuthenticationHelper;
import org.salesforce.demo.auth.AuthenticationHelper.AuthenticationException;
import org.salesforce.demo.utils.Config;
import org.salesforce.demo.auth.SalesforceSession;

public class PubSubApiClient {
	private static final Logger logger = Logger.getLogger(PubSubApiClient.class.getName());

	private boolean isShuttingDown = false;
	private ManagedChannel channel;
	private PubSubGrpc.PubSubStub stub;
	private PubSubGrpc.PubSubBlockingStub blockingStub;

	public void connect(Config config) throws PubSubException {

		logger.info("PubSub API: retrieving Salesforce session...");
		SalesforceSession session;
		try {
			session = AuthenticationHelper.login(config.getLoginUrl(), config.getUsername(), config.getPassword(),
					config.getToken());
		} catch (AuthenticationException e) {
			throw new PubSubException("Failed to retrieve Salesforce session: " + e.getMessage(), e);
		}

		logger.info("PubSub API: connecting to " + config.getPubSubEndpoint() + "...");
		try {
			// Prepare metadata with auth information
			Metadata metadata = new Metadata();
			metadata.put(Metadata.Key.of("accesstoken", Metadata.ASCII_STRING_MARSHALLER), session.getAccessToken());
			metadata.put(Metadata.Key.of("instanceurl", Metadata.ASCII_STRING_MARSHALLER), session.getInstanceUrl());
			metadata.put(Metadata.Key.of("tenantid", Metadata.ASCII_STRING_MARSHALLER), session.getOrgId());

			System.out.println(metadata);

			// Inject metadata in all client requests with an interceptor
			ClientInterceptor interceptor = MetadataUtils.newAttachHeadersInterceptor(metadata);
			channel = ManagedChannelBuilder.forTarget(config.getPubSubEndpoint()).intercept(interceptor).build();

			// We use a non-blocking stub for subscribe request and async event handling
			stub = PubSubGrpc.newStub(channel);
			// We use a blocking stub for getTopic and getSchema requests (less code
			// required)
			blockingStub = PubSubGrpc.newBlockingStub(channel);
		} catch (Exception e) {
			throw new PubSubException("Failed to connect: " + e.getMessage(), e);
		}
	}

	public void disconnect() throws PubSubException {
		if (channel != null) {
			logger.info("PubSub API: disconnecting...");
			try {
				channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
			} catch (Exception e) {
				throw new PubSubException("Failed to disconnect: " + e.getMessage(), e);
			}
		}
	}

	private TopicInfo retrieveTopic(String topicName) throws PubSubException {
		logger.info("PubSub API: retrieving topic " + topicName + "...");
		TopicRequest request = TopicRequest.newBuilder().setTopicName(topicName).build();
		try {
			return blockingStub.getTopic(request);
		} catch (StatusRuntimeException e) {
			throw new PubSubException("Failed to retrieve topic " + topicName + ": " + e.getMessage(), e);
		}
	}

	public SchemaInfo retrieveTopicSchemaInfo(String topicName) throws PubSubException {
		TopicInfo topic = retrieveTopic(topicName);
		logger.info("PubSub API: retrieving schema for topic " + topicName + "...");
		SchemaRequest request = SchemaRequest.newBuilder().setSchemaId(topic.getSchemaId()).build();
		try {
			return blockingStub.getSchema(request);
		} catch (StatusRuntimeException e) {
			throw new PubSubException(
					"Failed to retrieve schema for topic " + topic.getTopicName() + ": " + e.getMessage(), e);
		}
	}

	public Schema retrieveTopicSchema(String topicName) throws PubSubException {
		return new Schema.Parser().parse(retrieveTopicSchemaInfo(topicName).getSchemaJson());
	}

	public void subscribe(String topicName, Schema schema, int eventCountRequested) {
		logger.info(
				"PubSub API: subscribing to " + topicName + " and waiting for " + eventCountRequested + " events...");
		// Subscribe to incoming events
		stub.subscribe(new PubSubEventObserver(this, topicName, schema, eventCountRequested));
		// Keep program alive while we listen, we shutdown after receiving the requested
		// number of events
		// WARNING: This is a hack for the demo, use a separate thread for production
		while (!this.isShuttingDown) {
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

	private ProducerEvent generateProducerEvent(SchemaInfo schemaInfo) throws IOException {
		GenericRecord event = new GenericRecordBuilder(new Schema.Parser().parse(schemaInfo.getSchemaJson()))
				.set("CreatedDate", System.currentTimeMillis() / 1000)
				.set("CreatedById", "005b0000001BqtxAAC")
//				.set("Loan_Application_Id__c", UUID.randomUUID().toString())
				.set("Event_Name__c", "EventNameTest")
				.build();

		// Convert to byte array
		GenericDatumWriter<GenericRecord> writer = new GenericDatumWriter<>(event.getSchema());
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		BinaryEncoder encoder = EncoderFactory.get().directBinaryEncoder(buffer, null);
		writer.write(event, encoder);

		return ProducerEvent.newBuilder().setSchemaId(schemaInfo.getSchemaId())
				.setPayload(ByteString.copyFrom(buffer.toByteArray())).build();
	}

	public void publish(String topicName) throws IOException, PubSubException {
		SchemaInfo schemaInfo = retrieveTopicSchemaInfo(topicName);
		System.out.println("SchemaInfo: " + schemaInfo);

		ProducerEvent e = generateProducerEvent(schemaInfo);
		System.out.println("Generated ProducerEvent " + e);

		PublishRequest request = PublishRequest.newBuilder().setTopicName(topicName).addEvents(e).build();
		System.out.println("Generated PublishRequest " + request);

		PublishResponse response = blockingStub.publish(request);
		System.out.println("PublishRespons: " + response);
	}

	public void shutdown() {
		this.isShuttingDown = true;
	}

	public static void main(String[] args) throws Exception {
		Config config = Config.get();
		PubSubApiClient client = new PubSubApiClient();
		try {
			client.connect(config);
//			Schema topicSchema = client.retrieveTopicSchema(config.getPubSubTopicName());
//
//			System.out.println(topicSchema);
			System.out.println(client.retrieveTopic(config.getPubSubTopicName()));

//			client.publish(config.getPubSubTopicName());
//			client.subscribe(config.getPubSubTopicName(), topicSchema, config.getPubSubEventReceiveLimit());
		} catch (Throwable e) {
			System.out.println("BAHS" + e);
			logger.log(Level.SEVERE, e.getMessage(), e);
		} finally {
			client.disconnect();
			logger.info("PubSub API: client disconnected.");
		}
	}

	public static class PubSubException extends Exception {
		private static final long serialVersionUID = 8021594738096542039L;

		public PubSubException(String message, Exception causedBy) {
			super(message, causedBy);
		}
	};
}
