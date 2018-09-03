package exec.episodeMining;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cc.episodeMining.algorithm.ShellCommand;
import cc.episodeMining.data.EventStreamGenerator;
import cc.episodeMining.data.EventsFilter;
import cc.episodeMining.data.SequenceGenerator;
import cc.episodeMining.mudetect.EpisodesToPatternTransformer;
import cc.episodeMining.mudetect.TraceToAUGTransformer;
import cc.kave.episodes.io.EpisodeParser;
import cc.kave.episodes.io.EventStreamIo;
import cc.kave.episodes.io.FileReader;
import cc.kave.episodes.mining.patterns.ParallelPatterns;
import cc.kave.episodes.mining.patterns.PartialPatterns;
import cc.kave.episodes.mining.patterns.PatternFilter;
import cc.kave.episodes.mining.patterns.SequentialPatterns;
import cc.kave.episodes.model.Episode;
import cc.kave.episodes.model.EpisodeType;
import cc.kave.episodes.model.events.Event;
import cc.recommenders.datastructures.Tuple;
import cc.recommenders.io.Logger;

import com.google.common.collect.Lists;

import de.tu_darmstadt.stg.mubench.DataEdgeTypePriorityOrder;
import de.tu_darmstadt.stg.mubench.DefaultFilterAndRankingStrategy;
import de.tu_darmstadt.stg.mubench.ViolationUtils;
import de.tu_darmstadt.stg.mubench.cli.DetectionStrategy;
import de.tu_darmstadt.stg.mubench.cli.DetectorArgs;
import de.tu_darmstadt.stg.mubench.cli.DetectorOutput;
import de.tu_darmstadt.stg.mubench.cli.MuBenchRunner;
import de.tu_darmstadt.stg.mudetect.MissingElementViolationPredicate;
import de.tu_darmstadt.stg.mudetect.MuDetect;
import de.tu_darmstadt.stg.mudetect.VeryUnspecificReceiverTypePredicate;
import de.tu_darmstadt.stg.mudetect.aug.model.APIUsageExample;
import de.tu_darmstadt.stg.mudetect.aug.model.controlflow.OrderEdge;
import de.tu_darmstadt.stg.mudetect.aug.model.patterns.APIUsagePattern;
import de.tu_darmstadt.stg.mudetect.aug.visitors.AUGLabelProvider;
import de.tu_darmstadt.stg.mudetect.aug.visitors.BaseAUGLabelProvider;
import de.tu_darmstadt.stg.mudetect.matcher.EquallyLabelledEdgeMatcher;
import de.tu_darmstadt.stg.mudetect.matcher.EquallyLabelledNodeMatcher;
import de.tu_darmstadt.stg.mudetect.model.Violation;
import de.tu_darmstadt.stg.mudetect.overlapsfinder.AlternativeMappingsOverlapsFinder;
import de.tu_darmstadt.stg.mudetect.ranking.ConstantNodeWeightFunction;
import de.tu_darmstadt.stg.mudetect.ranking.OverlapWithoutEdgesToMissingNodesWeightFunction;
import de.tu_darmstadt.stg.mudetect.ranking.PatternSupportWeightFunction;
import de.tu_darmstadt.stg.mudetect.ranking.ProductWeightFunction;
import de.tu_darmstadt.stg.mudetect.ranking.ViolationSupportWeightFunction;
import de.tu_darmstadt.stg.mudetect.ranking.WeightRankingStrategy;
import edu.iastate.cs.mudetect.mining.MinPatternActionsModel;

public class runner {

	private static FileReader reader = new FileReader();

	private static final int FREQUENCY = 2;
	private static final double ENTROPY = 0.5;
	private static final int BREAKER = 5000;

	private static final int THRESHFREQ = 20;
	private static final double THRESHENT = 0.5;
	private static final double THRESHSUBP = 1.0;

	public static void main(String[] args) throws Exception {
		new MuBenchRunner().withMineAndDetectStrategy(new Strategy()).run(args);

		Logger.log("done");
	}

	static class Strategy implements DetectionStrategy {

		public DetectorOutput detectViolations(DetectorArgs args,
				DetectorOutput.Builder output) throws Exception {
			Map<String, List<Tuple<Event, List<Event>>>> stream = parser(
					args.getTargetSrcPaths(), args.getDependencyClassPath());

			ShellCommand command = new ShellCommand(new File(getEventsPath()),
					new File(getAlgorithmPath()));
			command.execute(FREQUENCY, ENTROPY, BREAKER);

			EpisodeParser episodeParser = new EpisodeParser(new File(
					getEventsPath()), reader);
			Map<Integer, Set<Episode>> episodes = episodeParser
					.parser(FREQUENCY);
			System.out.println("Maximal episode size " + episodes.size());
			System.out.println("Number of episodes: " + counter(episodes));

			PatternFilter patternFilter = new PatternFilter(
					new PartialPatterns(), new SequentialPatterns(),
					new ParallelPatterns());
			Map<Integer, Set<Episode>> superepisodes = patternFilter
					.subEpisodes(episodes, THRESHSUBP);
			System.out
					.println("Number of episodes after filtering subepisodes: "
							+ counter(superepisodes));
			Map<Integer, Set<Episode>> patterns = patternFilter.filter(
					EpisodeType.GENERAL, superepisodes, THRESHFREQ, THRESHENT);
			System.out.println("Number of patterns: " + counter(patterns));

			// PatternStatistics statistics = new PatternStatistics();
			// statistics.compute(patterns);
			// statistics.DiscNodes(patterns);

			EventStreamIo esio = new EventStreamIo(new File(getEventsPath()));
			List<Event> mapping = esio.readMapping(FREQUENCY);
			Set<APIUsagePattern> augPatterns = new EpisodesToPatternTransformer()
					.transform(patterns, mapping);
			System.out.println("Number of patterns of APIUsage transformer: "
					+ augPatterns.size());

			Collection<APIUsageExample> targets = loadTargetAUGs(stream);
			AUGLabelProvider labelProvider = new BaseAUGLabelProvider();
			MuDetect detection = new MuDetect(
					new MinPatternActionsModel(() -> augPatterns, 2),
					new AlternativeMappingsOverlapsFinder(
							new AlternativeMappingsOverlapsFinder.Config() {
								{
									isStartNode = super.isStartNode
											.and(new VeryUnspecificReceiverTypePredicate()
													.negate());
									nodeMatcher = new EquallyLabelledNodeMatcher(
											labelProvider);
									edgeMatcher = new EquallyLabelledEdgeMatcher(
											labelProvider);
									edgeOrder = new DataEdgeTypePriorityOrder();
									extensionEdgeTypes = new HashSet<>(Arrays
											.asList(OrderEdge.class));
								}
							}),
					new MissingElementViolationPredicate(),
					new DefaultFilterAndRankingStrategy(
							new WeightRankingStrategy(
									new ProductWeightFunction(
											new OverlapWithoutEdgesToMissingNodesWeightFunction(
													new ConstantNodeWeightFunction()),
											new PatternSupportWeightFunction(),
											new ViolationSupportWeightFunction()))));
			List<Violation> violations = detection.findViolations(targets);
			// List<Violation> violations = Lists.newLinkedList();
			return output.withFindings(violations, ViolationUtils::toFinding);
		}

		private int counter(Map<Integer, Set<Episode>> patterns) {
			int counter = 0;
			for (Map.Entry<Integer, Set<Episode>> entry : patterns.entrySet()) {
				counter += entry.getValue().size();
			}
			return counter;
		}

		private Collection<APIUsageExample> loadTargetAUGs(
				Map<String, List<Tuple<Event, List<Event>>>> traces)
				throws IOException {

			Collection<APIUsageExample> targets = new ArrayList<>();
			for (Map.Entry<String, List<Tuple<Event, List<Event>>>> entry : traces
					.entrySet()) {
				for (Tuple<Event, List<Event>> tuple : entry.getValue()) {
					targets.add(TraceToAUGTransformer.transform(entry.getKey(),
							tuple.getFirst(), tuple.getSecond()));
				}
			}
			return targets;
		}

		private Map<String, List<Tuple<Event, List<Event>>>> parser(
				String[] srcPaths, String[] classpaths) throws IOException {
			List<Event> sequences = buildMethodTraces(srcPaths, classpaths);

			EventsFilter ef = new EventsFilter();
			List<Event> localFilter = ef.locals(sequences);
			System.out
					.println("Number of events without project specific APIs: "
							+ localFilter.size());

			List<Event> duplicateFilter = ef.duplicates(localFilter);
			System.out.println("Number of events without duplicates: "
					+ duplicateFilter.size());

			List<Event> frequentFilter = ef
					.frequent(duplicateFilter, FREQUENCY);
			System.out.println("Number of frequent events: "
					+ frequentFilter.size());

			EventStreamGenerator esg = new EventStreamGenerator();
			Map<String, List<Tuple<Event, List<Event>>>> results = esg
					.fileMethodStructure(frequentFilter);
			getNoFiles(results);
			esg.generateFiles(new File(getEventsPath()), results);

			return results;
		}

		private void getNoFiles(
				Map<String, List<Tuple<Event, List<Event>>>> stream) {
			int methodCounter = 0;

			for (Map.Entry<String, List<Tuple<Event, List<Event>>>> entry : stream
					.entrySet()) {
				methodCounter += entry.getValue().size();
			}
			System.out.println("Number of classes: " + stream.size());
			System.out.println("Number of methods: " + methodCounter);
		}

		private List<Event> buildMethodTraces(String[] srcPaths,
				String[] classpaths) {
			List<Event> sequences = Lists.newLinkedList();
			for (String srcPath : srcPaths) {
				SequenceGenerator generator = new SequenceGenerator();
				sequences.addAll(generator.generateMethodTraces(new File(
						srcPath), classpaths));
			}
			return sequences;
		}

		private String getEventsPath() {
			String pathName = "/Users/ervinacergani/Documents/MisuseDetector/events/freq"
					+ FREQUENCY + "/";
			return pathName;
		}

		private String getAlgorithmPath() {
			String path = "/Users/ervinacergani/Documents/EpisodeMining/n-graph-miner/";
			return path;
		}
	}
}
