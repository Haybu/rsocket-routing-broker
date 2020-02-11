/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rsocket.routing.broker.spring;

import java.util.stream.Collectors;

import io.rsocket.SocketAcceptor;
import io.rsocket.routing.broker.Broker;
import io.rsocket.routing.broker.RSocketIndex;
import io.rsocket.routing.broker.RoutingTable;
import io.rsocket.routing.broker.acceptor.BrokerSocketAcceptor;
import io.rsocket.routing.broker.acceptor.ClusterSocketAcceptor;
import io.rsocket.routing.broker.config.AbstractBrokerProperties;
import io.rsocket.routing.broker.config.ClusterBrokerProperties;
import io.rsocket.routing.broker.config.TcpBrokerProperties;
import io.rsocket.routing.broker.config.WebsocketBrokerProperties;
import io.rsocket.routing.broker.locator.RemoteRSocketLocator;
import io.rsocket.routing.frames.Address;
import io.rsocket.routing.frames.RouteSetup;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.rsocket.RSocketStrategiesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.boot.rsocket.context.RSocketServerBootstrap;
import org.springframework.boot.rsocket.netty.NettyRSocketServer;
import org.springframework.boot.rsocket.netty.NettyRSocketServerFactory;
import org.springframework.boot.rsocket.server.RSocketServer;
import org.springframework.boot.rsocket.server.RSocketServerFactory;
import org.springframework.boot.rsocket.server.ServerRSocketFactoryProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorResourceFactory;
import org.springframework.messaging.rsocket.DefaultMetadataExtractor;
import org.springframework.messaging.rsocket.MetadataExtractor;
import org.springframework.messaging.rsocket.RSocketStrategies;

@Configuration
@EnableConfigurationProperties
@AutoConfigureAfter({RSocketStrategiesAutoConfiguration.class, BrokerRSocketStrategiesAutoConfiguration.class})
public class BrokerAutoConfiguration implements InitializingBean {

	public static final String BROKER_PREFIX = "io.rsocket.routing.broker";

	private final ApplicationContext context;

	public BrokerAutoConfiguration(ApplicationContext context) {
		this.context = context;
	}

	@Override
	public void afterPropertiesSet() {
		RSocketStrategies rSocketStrategies = this.context
				.getBean(RSocketStrategies.class);
		MetadataExtractor metadataExtractor = rSocketStrategies.metadataExtractor();

		if (metadataExtractor instanceof DefaultMetadataExtractor) {
			DefaultMetadataExtractor extractor = (DefaultMetadataExtractor) metadataExtractor;
			extractor.metadataToExtract(MimeTypes.ADDRESS_MIME_TYPE, Address.class,
					Address.METADATA_KEY);
			extractor.metadataToExtract(MimeTypes.ROUTE_SETUP_MIME_TYPE, RouteSetup.class,
					RouteSetup.METADATA_KEY);
		}
	}

	@Bean
	// TODO: Broker class needed?
	public Broker broker() {
		return new Broker();
	}

	@Bean
	public RSocketIndex rSocketIndex() {
		return new RSocketIndex();
	}

	@Bean
	public RoutingTable routingTable() {
		return new RoutingTable();
	}

	@Bean
	@ConditionalOnMissingBean
	public ReactorResourceFactory reactorResourceFactory() {
		return new ReactorResourceFactory();
	}

	@Bean
	public RemoteRSocketLocator remoteRSocketLocator(RoutingTable routingTable, RSocketIndex index) {
		return new RemoteRSocketLocator(routingTable, index);
	}

	@Bean
	public MetadataExtractorBrokerSocketAcceptor metadataExtractorBrokerSocketAcceptor(
			RSocketStrategies rSocketStrategies, RoutingTable routingTable,
			RSocketIndex rSocketIndex, RemoteRSocketLocator locator) {
		return new MetadataExtractorBrokerSocketAcceptor(routingTable, rSocketIndex, locator,
				rSocketStrategies.metadataExtractor());
	}

	private static RSocketServerFactory getRSocketServerFactory(ReactorResourceFactory resourceFactory,
			ObjectProvider<ServerRSocketFactoryProcessor> processors, AbstractBrokerProperties properties) {
		NettyRSocketServerFactory factory = new NettyRSocketServerFactory();
		factory.setResourceFactory(resourceFactory);
		factory.setTransport(getTransport(properties));
		PropertyMapper map = PropertyMapper.get().alwaysApplyingWhenNonNull();
		map.from(properties.getAddress()).to(factory::setAddress);
		map.from(properties.getPort()).to(factory::setPort);
		factory.setSocketFactoryProcessors(processors.orderedStream().collect(Collectors
				.toList()));
		return factory;
	}

	private static RSocketServer.Transport getTransport(AbstractBrokerProperties properties) {
		switch (properties.getTransport()) {
		case TCP:
			return RSocketServer.Transport.TCP;
		case WEBSOCKET:
			return RSocketServer.Transport.WEBSOCKET;
		}
		throw new IllegalStateException("Unknown Transport " + properties.getTransport());
	}

	@Configuration
	@ConditionalOnProperty(name = ClusterConfiguration.PREFIX + ".enabled", matchIfMissing = true)
	protected static class ClusterConfiguration {

		public static final String PREFIX = BROKER_PREFIX + ".cluster";

		@Bean
		public ClusterSocketAcceptor clusterSocketAcceptor() {
			return new ClusterSocketAcceptor();
		}

		@Bean
		@ConfigurationProperties(PREFIX)
		public ClusterBrokerProperties clusterBrokerProperties() {
			return new ClusterBrokerProperties();
		}

		@Bean
		public RSocketServerBootstrap clusterRSocketServerBootstrap(
				ClusterBrokerProperties properties,
				ReactorResourceFactory resourceFactory,
				ObjectProvider<ServerRSocketFactoryProcessor> processors,
				ClusterSocketAcceptor clusterSocketAcceptor) {
			RSocketServerFactory serverFactory = getRSocketServerFactory(resourceFactory, processors, properties);
			return new BrokerRSocketServerBootstrap(properties, serverFactory, clusterSocketAcceptor);
		}
	}

	@Configuration
	@ConditionalOnProperty(name = TcpConfiguration.PREFIX + ".enabled", matchIfMissing = true)
	protected static class TcpConfiguration {

		public static final String PREFIX = BROKER_PREFIX + ".tcp";

		@Bean
		@ConfigurationProperties(PREFIX)
		public TcpBrokerProperties tcpBrokerProperties() {
			return new TcpBrokerProperties();
		}

		@Bean
		public RSocketServerBootstrap tcpRSocketServerBootstrap(
				TcpBrokerProperties properties,
				ReactorResourceFactory resourceFactory,
				ObjectProvider<ServerRSocketFactoryProcessor> processors,
				BrokerSocketAcceptor brokerSocketAcceptor) {
			RSocketServerFactory serverFactory = getRSocketServerFactory(resourceFactory, processors, properties);
			return new BrokerRSocketServerBootstrap(properties, serverFactory, brokerSocketAcceptor);
		}
	}

	@Configuration
	@ConditionalOnProperty(name = WebsocketConfiguration.PREFIX + ".enabled")
	protected static class WebsocketConfiguration {
		public static final String PREFIX = BROKER_PREFIX + ".websocket";

		@Bean
		@ConfigurationProperties(PREFIX)
		public WebsocketBrokerProperties websocketBrokerProperties() {
			return new WebsocketBrokerProperties();
		}

		@Bean
		public RSocketServerBootstrap websocketRSocketServerBootstrap(
				WebsocketBrokerProperties properties,
				ReactorResourceFactory resourceFactory,
				ObjectProvider<ServerRSocketFactoryProcessor> processors,
				BrokerSocketAcceptor brokerSocketAcceptor) {
			RSocketServerFactory serverFactory = getRSocketServerFactory(resourceFactory, processors, properties);
			return new BrokerRSocketServerBootstrap(properties, serverFactory, brokerSocketAcceptor);
		}
	}

	private static class BrokerRSocketServerBootstrap extends RSocketServerBootstrap {

		// purposefully using NettyRSocketServer
		private static final Log logger = LogFactory.getLog(NettyRSocketServer.class);
		private final AbstractBrokerProperties properties;

		public BrokerRSocketServerBootstrap(AbstractBrokerProperties properties,
				RSocketServerFactory serverFactory,
				SocketAcceptor socketAcceptor) {
			super(serverFactory, socketAcceptor);
			this.properties = properties;
		}

		@Override
		public void start() {
			logger.info("Netty RSocket starting transport: " + properties.getTransport()
					+ " " + properties.getType());
			super.start();
		}
	}

}