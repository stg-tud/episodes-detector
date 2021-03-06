package cc.episodeMining.mappings;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import de.tu_darmstadt.stg.mudetect.OverlapsFinder;
import de.tu_darmstadt.stg.mudetect.aug.model.APIUsageExample;
import de.tu_darmstadt.stg.mudetect.aug.model.Edge;
import de.tu_darmstadt.stg.mudetect.aug.model.Node;
import de.tu_darmstadt.stg.mudetect.aug.model.patterns.APIUsagePattern;
import de.tu_darmstadt.stg.mudetect.model.Overlap;
import de.tu_darmstadt.stg.mudetect.overlapsfinder.AlternativeMappingsOverlapsFinder;
import de.tu_darmstadt.stg.mudetect.overlapsfinder.AlternativeMappingsOverlapsFinder.Config;

public class DisconnectedPatternsOverlapFinder implements OverlapsFinder {

	private Config config;

	private OverlapsFinder of;

	public DisconnectedPatternsOverlapFinder(Config config) {
		this.config = config;

		of = new AlternativeMappingsOverlapsFinder(config);
	}

	@Override
	public List<Overlap> findOverlaps(APIUsageExample target,
			APIUsagePattern pattern) {
		Set<APIUsagePattern> subPatterns = new SubpatternsGenerator()
				.generate(pattern);
		if (subPatterns.size() == 1) {
			return of.findOverlaps(target, subPatterns.iterator().next());
		}

		List<List<Overlap>> spOverlaps = Lists.newLinkedList();
		for (APIUsagePattern aup : subPatterns) {
			List<Overlap> overlaps = of.findOverlaps(target, aup);
			spOverlaps.add(overlaps);
		}
		if (spOverlaps.size() == 2) {
			List<List<Overlap>> tmp = Lists.newLinkedList();
			tmp.add(spOverlaps.get(1));
			return combineOverlaps(target, spOverlaps.get(0), tmp);
		}
		List<List<Overlap>> tmp = spOverlaps.subList(1, spOverlaps.size() - 1);
		return combineOverlaps(target, spOverlaps.get(0), tmp);
	}

	private List<Overlap> combineOverlaps(APIUsageExample target,
			List<Overlap> list, List<List<Overlap>> subList) {
		if (subList.size() == 1) {
			return combinePairs(target, list, subList.get(0));
		}
		if (subList.size() == 2) {
			List<List<Overlap>> tmp = Lists.newLinkedList();
			tmp.add(subList.get(1));
			return combineOverlaps(target, subList.get(0), tmp);
		}
		List<List<Overlap>> tmp = subList.subList(1, subList.size() - 1);
		return combineOverlaps(target, subList.get(0), tmp);
	}

	private List<Overlap> combinePairs(APIUsageExample target,
			List<Overlap> ovs1, List<Overlap> ovs2) {
		List<Overlap> combinedOverlaps = Lists.newLinkedList();

		for (Overlap o1 : ovs1) {
			for (Overlap o2 : ovs2) {
				APIUsagePattern p1 = o1.getPattern();
				APIUsagePattern p2 = o2.getPattern();

				APIUsagePattern pattern = generatePattern(p1, p2);
				Map<Node, Node> targetNodeByPatternNode = Maps
						.newLinkedHashMap();

				for (Node patternNode : p1.vertexSet()) {
					Node targetNode = o1.getMappedTargetNode(patternNode);
					if (targetNode != null) {
						targetNodeByPatternNode.put(patternNode, targetNode);
					}
				}
				for (Node patternNode : p2.vertexSet()) {
					Node targetNode = o2.getMappedTargetNode(patternNode);
					if (targetNode != null) {
						targetNodeByPatternNode.put(patternNode, targetNode);
					}
				}
				Map<Edge, Edge> targetEdgeByPatternEdge = Maps
						.newLinkedHashMap();

				for (Edge patternEdge : pattern.edgeSet()) {
					if (o1.mapsEdge(patternEdge) || o2.mapsEdge(patternEdge)) {
						Node pSource = patternEdge.getSource();
						Node pTarget = patternEdge.getTarget();

						Node tSource = targetNodeByPatternNode.get(pSource);
						Node tTarget = targetNodeByPatternNode.get(pTarget);
						Edge targetEdge = findTargetEdge(tSource, tTarget,
								target);

						if (targetEdge != null) {
							targetEdgeByPatternEdge
									.put(patternEdge, targetEdge);
						}
					}
				}
				Overlap overlap = new Overlap(pattern, target,
						targetNodeByPatternNode, targetEdgeByPatternEdge);
				combinedOverlaps.add(overlap);
			}
		}
		return combinedOverlaps;
	}

	private Edge findTargetEdge(Node tSource, Node tTarget,
			APIUsageExample target) {
		for (Edge edge : target.edgeSet()) {
			if (edge.getSource().equals(tSource)
					&& edge.getTarget().equals(tTarget)) {
				return edge;
			}
		}
		return null;
	}

	private APIUsagePattern generatePattern(APIUsagePattern p1,
			APIUsagePattern p2) {
		APIUsagePattern pattern = new APIUsagePattern(p1.getSupport(),
				new HashSet<>());
		for (Node node : p1.vertexSet()) {
			pattern.addVertex(node);
		}
		for (Node node : p2.vertexSet()) {
			pattern.addVertex(node);
		}
		for (Edge edge : p1.edgeSet()) {
			pattern.addEdge(edge.getSource(), edge.getTarget(), edge);
		}
		for (Edge edge : p2.edgeSet()) {
			pattern.addEdge(edge.getSource(), edge.getTarget(), edge);
		}
		return pattern;
	}
}
