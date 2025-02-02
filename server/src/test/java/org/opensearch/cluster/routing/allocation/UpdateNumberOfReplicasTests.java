/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.cluster.routing.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.Version;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.OpenSearchAllocationTestCase;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.common.settings.Settings;

import static org.opensearch.cluster.routing.ShardRoutingState.INITIALIZING;
import static org.opensearch.cluster.routing.ShardRoutingState.STARTED;
import static org.opensearch.cluster.routing.ShardRoutingState.UNASSIGNED;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

public class UpdateNumberOfReplicasTests extends OpenSearchAllocationTestCase {
    private final Logger logger = LogManager.getLogger(UpdateNumberOfReplicasTests.class);

    public void testUpdateNumberOfReplicas() {
        AllocationService strategy = createAllocationService(Settings.builder()
            .put("cluster.routing.allocation.node_concurrent_recoveries", 10).build());

        logger.info("Building initial routing table");

        Metadata metadata = Metadata.builder()
                .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1))
                .build();

        RoutingTable initialRoutingTable = RoutingTable.builder()
                .addAsNew(metadata.index("test"))
                .build();

        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING
            .getDefault(Settings.EMPTY)).metadata(metadata).routingTable(initialRoutingTable).build();

        assertThat(initialRoutingTable.index("test").shards().size(), equalTo(1));
        assertThat(initialRoutingTable.index("test").shard(0).size(), equalTo(2));
        assertThat(initialRoutingTable.index("test").shard(0).shards().size(), equalTo(2));
        assertThat(initialRoutingTable.index("test").shard(0).shards().get(0).state(), equalTo(UNASSIGNED));
        assertThat(initialRoutingTable.index("test").shard(0).shards().get(1).state(), equalTo(UNASSIGNED));
        assertThat(initialRoutingTable.index("test").shard(0).shards().get(0).currentNodeId(), nullValue());
        assertThat(initialRoutingTable.index("test").shard(0).shards().get(1).currentNodeId(), nullValue());


        logger.info("Adding two nodes and performing rerouting");
        clusterState = ClusterState.builder(clusterState)
            .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2"))).build();

        clusterState = strategy.reroute(clusterState, "reroute");

        logger.info("Start all the primary shards");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        logger.info("Start all the replica shards");
        ClusterState newState = startInitializingShardsAndReroute(strategy, clusterState);
        assertThat(newState, not(equalTo(clusterState)));
        clusterState = newState;

        final String nodeHoldingPrimary = clusterState.routingTable().index("test").shard(0).primaryShard().currentNodeId();
        final String nodeHoldingReplica = clusterState.routingTable().index("test").shard(0).replicaShards().get(0).currentNodeId();
        assertThat(nodeHoldingPrimary, not(equalTo(nodeHoldingReplica)));
        assertThat(clusterState.routingTable().index("test").shards().size(), equalTo(1));
        assertThat(clusterState.routingTable().index("test").shard(0).size(), equalTo(2));
        assertThat(clusterState.routingTable().index("test").shard(0).shards().size(), equalTo(2));
        assertThat(clusterState.routingTable().index("test").shard(0).primaryShard().state(), equalTo(STARTED));
        assertThat(clusterState.routingTable().index("test").shard(0).primaryShard().currentNodeId(), equalTo(nodeHoldingPrimary));
        assertThat(clusterState.routingTable().index("test").shard(0).replicaShards().size(), equalTo(1));
        assertThat(clusterState.routingTable().index("test").shard(0).replicaShards().get(0).state(), equalTo(STARTED));
        assertThat(clusterState.routingTable().index("test").shard(0).replicaShards().get(0).currentNodeId(),
            equalTo(nodeHoldingReplica));


        logger.info("add another replica");
        final String[] indices = {"test"};
        RoutingTable updatedRoutingTable =
                RoutingTable.builder(clusterState.routingTable()).updateNumberOfReplicas(2, indices).build();
        metadata = Metadata.builder(clusterState.metadata()).updateNumberOfReplicas(2, indices).build();
        clusterState = ClusterState.builder(clusterState).routingTable(updatedRoutingTable).metadata(metadata).build();

        assertThat(clusterState.metadata().index("test").getNumberOfReplicas(), equalTo(2));

        assertThat(clusterState.routingTable().index("test").shards().size(), equalTo(1));
        assertThat(clusterState.routingTable().index("test").shard(0).size(), equalTo(3));
        assertThat(clusterState.routingTable().index("test").shard(0).primaryShard().state(), equalTo(STARTED));
        assertThat(clusterState.routingTable().index("test").shard(0).primaryShard().currentNodeId(), equalTo(nodeHoldingPrimary));
        assertThat(clusterState.routingTable().index("test").shard(0).replicaShards().size(), equalTo(2));
        assertThat(clusterState.routingTable().index("test").shard(0).replicaShards().get(0).state(), equalTo(STARTED));
        assertThat(clusterState.routingTable().index("test").shard(0).replicaShards().get(0).currentNodeId(),
            equalTo(nodeHoldingReplica));
        assertThat(clusterState.routingTable().index("test").shard(0).replicaShards().get(1).state(), equalTo(UNASSIGNED));

        logger.info("Add another node and start the added replica");
        clusterState = ClusterState.builder(clusterState)
            .nodes(DiscoveryNodes.builder(clusterState.nodes()).add(newNode("node3"))).build();
        newState = strategy.reroute(clusterState, "reroute");
        assertThat(newState, not(equalTo(clusterState)));
        clusterState = newState;

        assertThat(clusterState.routingTable().index("test").shards().size(), equalTo(1));
        assertThat(clusterState.routingTable().index("test").shard(0).size(), equalTo(3));
        assertThat(clusterState.routingTable().index("test").shard(0).primaryShard().state(), equalTo(STARTED));
        assertThat(clusterState.routingTable().index("test").shard(0).primaryShard().currentNodeId(), equalTo(nodeHoldingPrimary));
        assertThat(clusterState.routingTable().index("test").shard(0).replicaShards().size(), equalTo(2));
        assertThat(clusterState.routingTable().index("test").shard(0).replicaShardsWithState(STARTED).size(), equalTo(1));
        assertThat(clusterState.routingTable().index("test").shard(0).replicaShardsWithState(STARTED).get(0).currentNodeId(),
            equalTo(nodeHoldingReplica));
        assertThat(clusterState.routingTable().index("test").shard(0).replicaShardsWithState(INITIALIZING).size(), equalTo(1));
        assertThat(clusterState.routingTable().index("test").shard(0).replicaShardsWithState(INITIALIZING).get(0).currentNodeId(),
            equalTo("node3"));

        newState = startInitializingShardsAndReroute(strategy, clusterState);
        assertThat(newState, not(equalTo(clusterState)));
        clusterState = newState;

        assertThat(clusterState.routingTable().index("test").shards().size(), equalTo(1));
        assertThat(clusterState.routingTable().index("test").shard(0).size(), equalTo(3));
        assertThat(clusterState.routingTable().index("test").shard(0).primaryShard().state(), equalTo(STARTED));
        assertThat(clusterState.routingTable().index("test").shard(0).primaryShard().currentNodeId(), equalTo(nodeHoldingPrimary));
        assertThat(clusterState.routingTable().index("test").shard(0).replicaShards().size(), equalTo(2));
        assertThat(clusterState.routingTable().index("test").shard(0).replicaShardsWithState(STARTED).size(), equalTo(2));
        assertThat(clusterState.routingTable().index("test").shard(0).replicaShardsWithState(STARTED).get(0).currentNodeId(),
            anyOf(equalTo(nodeHoldingReplica), equalTo("node3")));
        assertThat(clusterState.routingTable().index("test").shard(0).replicaShardsWithState(STARTED).get(1).currentNodeId(),
            anyOf(equalTo(nodeHoldingReplica), equalTo("node3")));

        logger.info("now remove a replica");
        updatedRoutingTable = RoutingTable.builder(clusterState.routingTable()).updateNumberOfReplicas(1, indices).build();
        metadata = Metadata.builder(clusterState.metadata()).updateNumberOfReplicas(1, indices).build();
        clusterState = ClusterState.builder(clusterState).routingTable(updatedRoutingTable).metadata(metadata).build();

        assertThat(clusterState.metadata().index("test").getNumberOfReplicas(), equalTo(1));

        assertThat(clusterState.routingTable().index("test").shards().size(), equalTo(1));
        assertThat(clusterState.routingTable().index("test").shard(0).size(), equalTo(2));
        assertThat(clusterState.routingTable().index("test").shard(0).primaryShard().state(), equalTo(STARTED));
        assertThat(clusterState.routingTable().index("test").shard(0).primaryShard().currentNodeId(), equalTo(nodeHoldingPrimary));
        assertThat(clusterState.routingTable().index("test").shard(0).replicaShards().size(), equalTo(1));
        assertThat(clusterState.routingTable().index("test").shard(0).replicaShards().get(0).state(), equalTo(STARTED));
        assertThat(clusterState.routingTable().index("test").shard(0).replicaShards().get(0).currentNodeId(),
            anyOf(equalTo(nodeHoldingReplica), equalTo("node3")));

        logger.info("do a reroute, should remain the same");
        newState = strategy.reroute(clusterState, "reroute");
        assertThat(newState, equalTo(clusterState));
    }
}
