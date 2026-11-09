package com.campus.reassignment.service.optimal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * High-performance, self-contained Min-Cost Max-Flow (MCMF) graph solver using
 * the Successive Shortest Path (SSP) algorithm with Dijkstra and Johnson's node potentials.
 *
 * <p>Potentials guarantee non-negative reduced costs {@code cost + h[u] - h[v] >= 0},
 * enabling Dijkstra's O(E log V) shortest path search per augmentation step.
 */
public class MinCostMaxFlow {

    public static class Edge {
        public final int from;
        public final int to;
        public final int rev;
        public int capacity;
        public int flow;
        public final int cost;

        public Edge(int from, int to, int rev, int capacity, int cost) {
            this.from = from;
            this.to = to;
            this.rev = rev;
            this.capacity = capacity;
            this.flow = 0;
            this.cost = cost;
        }

        public int remainingCapacity() {
            return capacity - flow;
        }
    }

    private final int nodeCount;
    private final List<List<Edge>> graph;

    public MinCostMaxFlow(int nodeCount) {
        this.nodeCount = nodeCount;
        this.graph = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            this.graph.add(new ArrayList<>());
        }
    }

    /**
     * Adds a directed capacity edge with a specified unit cost, and a 0-capacity reverse edge with -cost.
     */
    public Edge addEdge(int from, int to, int capacity, int cost) {
        int fromIdx = graph.get(from).size();
        int toIdx = graph.get(to).size();
        if (from == to) {
            toIdx++;
        }
        Edge forward = new Edge(from, to, toIdx, capacity, cost);
        Edge backward = new Edge(to, from, fromIdx, 0, -cost);
        graph.get(from).add(forward);
        graph.get(to).add(backward);
        return forward;
    }

    public List<Edge> getEdgesFrom(int node) {
        return graph.get(node);
    }

    public record Result(int totalFlow, int totalCost) {}

    /**
     * Solves for maximum flow from source to sink up to {@code targetFlow}, minimizing total cost.
     */
    public Result solve(int source, int sink, int targetFlow) {
        int[] h = new int[nodeCount]; // Johnson potentials
        int[] dist = new int[nodeCount];
        Edge[] prevEdge = new Edge[nodeCount];

        int totalFlow = 0;
        int totalCost = 0;

        while (totalFlow < targetFlow) {
            Arrays.fill(dist, Integer.MAX_VALUE);
            dist[source] = 0;

            PriorityQueue<NodeDist> pq = new PriorityQueue<>(Comparator.comparingInt(nd -> nd.distance));
            pq.add(new NodeDist(source, 0));

            while (!pq.isEmpty()) {
                NodeDist curr = pq.poll();
                if (curr.distance > dist[curr.node]) {
                    continue;
                }

                for (Edge edge : graph.get(curr.node)) {
                    if (edge.remainingCapacity() > 0) {
                        int reducedCost = edge.cost + h[edge.from] - h[edge.to];
                        if (dist[edge.from] != Integer.MAX_VALUE && dist[edge.from] + reducedCost < dist[edge.to]) {
                            dist[edge.to] = dist[edge.from] + reducedCost;
                            prevEdge[edge.to] = edge;
                            pq.add(new NodeDist(edge.to, dist[edge.to]));
                        }
                    }
                }
            }

            // Sink not reachable -> cannot push more flow
            if (dist[sink] == Integer.MAX_VALUE) {
                break;
            }

            // Update potentials
            for (int i = 0; i < nodeCount; i++) {
                if (dist[i] != Integer.MAX_VALUE) {
                    h[i] += dist[i];
                }
            }

            // Find bottleneck capacity along the shortest path
            int push = targetFlow - totalFlow;
            for (int curr = sink; curr != source; curr = prevEdge[curr].from) {
                push = Math.min(push, prevEdge[curr].remainingCapacity());
            }

            // Apply augmentation
            for (int curr = sink; curr != source; curr = prevEdge[curr].from) {
                Edge forward = prevEdge[curr];
                forward.flow += push;
                graph.get(forward.to).get(forward.rev).flow -= push;
                totalCost += push * forward.cost;
            }

            totalFlow += push;
        }

        return new Result(totalFlow, totalCost);
    }

    private record NodeDist(int node, int distance) {}
}
