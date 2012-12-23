package org.encog.ml.prg.train;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.encog.Encog;
import org.encog.EncogError;
import org.encog.mathutil.randomize.factory.RandomFactory;
import org.encog.ml.MLMethod;
import org.encog.ml.TrainingImplementationType;
import org.encog.ml.data.MLDataSet;
import org.encog.ml.genetic.evolutionary.EvolutionaryOperator;
import org.encog.ml.genetic.evolutionary.OperationList;
import org.encog.ml.genetic.genome.Genome;
import org.encog.ml.genetic.population.Population;
import org.encog.ml.genetic.sort.MaximizeAdjustedScoreScoreComp;
import org.encog.ml.genetic.sort.MinimizeAdjustedScoreScoreComp;
import org.encog.ml.prg.EncogProgram;
import org.encog.ml.prg.EncogProgramContext;
import org.encog.ml.prg.EncogProgramVariables;
import org.encog.ml.prg.epl.EPLHolder;
import org.encog.ml.prg.exception.EncogProgramError;
import org.encog.ml.prg.train.selection.PrgSelection;
import org.encog.ml.prg.train.selection.TournamentSelection;
import org.encog.ml.train.MLTrain;
import org.encog.ml.train.strategy.Strategy;
import org.encog.neural.networks.training.CalculateScore;
import org.encog.neural.networks.training.TrainingSetScore;
import org.encog.neural.networks.training.propagation.TrainingContinuation;
import org.encog.util.concurrency.MultiThreadable;

public class PrgGenetic implements MLTrain, MultiThreadable {
	private final EncogProgramContext context;
	private final Population population;
	private final CalculateScore scoreFunction;
	private PrgSelection selection;
	private final EncogProgram bestGenome;
	private Comparator<Genome> compareScore;
	private int threadCount;
	private GeneticTrainWorker[] workers;
	private int iterationNumber;
	private int subIterationCounter;
	private final Lock iterationLock = new ReentrantLock();
	private RandomFactory randomNumberFactory = Encog.getInstance()
			.getRandomFactory().factorFactory();
	private Throwable currentError;
	private ThreadedGenomeSelector selector;
	private final OperationList operators = new OperationList();

	/**
	 * Condition used to check if we are done.
	 */
	private final Condition iterationCondition = this.iterationLock
			.newCondition();

	public PrgGenetic(PrgPopulation thePopulation,
			CalculateScore theScoreFunction) {
		this.population = thePopulation;
		this.context = thePopulation.getContext();
		this.scoreFunction = theScoreFunction;
		this.selection = new TournamentSelection(this, 4);
		
		this.bestGenome = thePopulation.createProgram();
		if (theScoreFunction.shouldMinimize()) {
			this.compareScore = new MinimizeAdjustedScoreScoreComp();
		} else {
			this.compareScore = new MaximizeAdjustedScoreScoreComp();
		}
		
		this.selector = new ThreadedGenomeSelector(this);
		
	}
	
	public void addOperation(double probability, EvolutionaryOperator opp) {
		this.operators.add(probability, opp);
	}

	public PrgGenetic(PrgPopulation thePopulation, MLDataSet theTrainingSet) {
		this(thePopulation, new TrainingSetScore(theTrainingSet));
	}

	public Population getPopulation() {
		return population;
	}

	public CalculateScore getScoreFunction() {
		return scoreFunction;
	}

	public PrgSelection getSelection() {
		return selection;
	}

	public void setSelection(PrgSelection selection) {
		this.selection = selection;
	}

	@Override
	public TrainingImplementationType getImplementationType() {
		// TODO Auto-generated method stub
		return TrainingImplementationType.Background;
	}

	@Override
	public boolean isTrainingDone() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public MLDataSet getTraining() {
		// TODO Auto-generated method stub
		return null;
	}

	private void startup() {
		int actualThreadCount = Runtime.getRuntime().availableProcessors();

		if (this.threadCount != 0) {
			actualThreadCount = this.threadCount;
		}

		this.workers = new GeneticTrainWorker[actualThreadCount];

		for (int i = 0; i < this.workers.length; i++) {
			this.workers[i] = new GeneticTrainWorker(this);
			this.workers[i].start();
		}

	}

	@Override
	public void iteration() {
		if (this.workers == null) {
			this.operators.finalizeStructure();
			startup();
		}

		this.iterationLock.lock();
		try {
			this.iterationCondition.await();
			if (this.currentError != null) {
				throw new EncogError(this.currentError);
			}
		} catch (InterruptedException e) {

		} finally {
			this.iterationLock.unlock();
		}
		
		if( this.currentError!=null ) {
			finishTraining();
		}
	}

	@Override
	public double getError() {
		return this.bestGenome.getScore();
	}

	@Override
	public void finishTraining() {
		for (int i = 0; i < this.workers.length; i++) {
			this.workers[i].requestTerminate();
		}

		for (int i = 0; i < this.workers.length; i++) {
			try {
				this.workers[i].join();
			} catch (InterruptedException e) {
				throw new EncogError("Can't shut down training threads.");
			}
		}

		this.workers = null;

	}

	@Override
	public void iteration(int count) {
		// TODO Auto-generated method stub

	}

	@Override
	public int getIteration() {
		return this.iterationNumber;
	}

	@Override
	public boolean canContinue() {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public TrainingContinuation pause() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void resume(TrainingContinuation state) {
		// TODO Auto-generated method stub

	}

	@Override
	public void addStrategy(Strategy strategy) {
		// TODO Auto-generated method stub

	}

	@Override
	public MLMethod getMethod() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public List<Strategy> getStrategies() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void setError(double error) {
		// TODO Auto-generated method stub

	}

	@Override
	public void setIteration(int iteration) {
		// TODO Auto-generated method stub

	}

	public Comparator<Genome> getCompareScore() {
		return compareScore;
	}

	public void setCompareScore(Comparator<Genome> compareScore) {
		this.compareScore = compareScore;
	}

	public void createRandomPopulation(int maxDepth) {
		CreateRandom rnd = new CreateRandom(this.context, maxDepth);
		Random random = this.randomNumberFactory.factor();
		EPLHolder holder = ((PrgPopulation)this.population).getHolder();

		this.population.getGenomes().clear();
		for (int i = 0; i < this.context.getParams().getPopulationSize(); i++) {
			EncogProgram prg = new EncogProgram(this.context, new EncogProgramVariables(), holder, i);
			this.population.getGenomes().add(prg);

			boolean done = false;
			do {
				prg.clear();
				rnd.createNode(random, prg, 0);
				double score = this.scoreFunction.calculateScore(prg);
				if (!Double.isInfinite(score) && !Double.isNaN(score)) {
					prg.setScore(score);
					done = true;
				}
			} while (!done);
			evaluateBestGenome(prg);

			this.population.rewrite(prg);
		}
	}

	private void evaluateBestGenome(EncogProgram prg) {
		this.iterationLock.lock();
		try {
			calculateEffectiveScore(prg);
			if (this.bestGenome.size()==0 || isGenomeBetter(prg, this.bestGenome)) {
				this.bestGenome.copy(prg);
			}
		} finally {
			this.iterationLock.unlock();
		}
		
	}

	public boolean isGenomeBetter(Genome genome, Genome betterThan) {
		return this.compareScore.compare(genome, betterThan) < 0;
	}

	public void copyBestGenome(EncogProgram target) {
		this.iterationLock.lock();
		try {
			target.copy(this.bestGenome);
		} finally {
			this.iterationLock.unlock();
		}
	}

	public void sort() {
		Collections.sort(this.getPopulation().getGenomes(), this.compareScore);

	}
	
	public PrgPopulation getPrgPopulation() {
		return (PrgPopulation)getPopulation();
	}

	public void addGenome(EncogProgram[] genome, int index, int size) {
		EncogProgram replaceTarget = null;
		this.iterationLock.lock();
		try {
			for(int i=0;i<size;i++) {
				if( genome[i].size()>getPrgPopulation().getHolder().getMaxIndividualSize() ) {
					throw new EncogProgramError("Program is too large to be added to population.");
				}
				replaceTarget = this.selector.antiSelectGenome();
				this.population.rewrite(genome[index+i]);
				replaceTarget.copy(genome[index+i]);
				evaluateBestGenome(genome[index+i]);
			}
		} finally {
			this.iterationLock.unlock();
			if( replaceTarget!=null ) {
				this.selector.releaseGenome(replaceTarget);
			}
		}
	}
	
	public void notifyProgress() {
		this.iterationLock.lock();
		try {			
			this.subIterationCounter++;
			if (this.subIterationCounter > this.population.size()) {
				this.subIterationCounter = 0;
				this.iterationNumber++;
				this.iterationCondition.signal();
			}
		} finally {
			this.iterationLock.unlock();
		}
	}

	@Override
	public int getThreadCount() {
		return this.threadCount;
	}

	@Override
	public void setThreadCount(int numThreads) {
		this.threadCount = numThreads;
	}

	/**
	 * @return the randomNumberFactory
	 */
	public RandomFactory getRandomNumberFactory() {
		return randomNumberFactory;
	}

	/**
	 * @param randomNumberFactory
	 *            the randomNumberFactory to set
	 */
	public void setRandomNumberFactory(RandomFactory randomNumberFactory) {
		this.randomNumberFactory = randomNumberFactory;
	}

	public void reportError(Throwable t) {
		this.iterationLock.lock();
		try {
			this.currentError = t;
			this.iterationCondition.signal();
		} finally {
			this.iterationLock.unlock();
		}
	}
	
	public void signalDone() {
		this.iterationLock.lock();
		try {
			this.iterationCondition.signal();
		} finally {
			this.iterationLock.unlock();
		}
	}
	
	

	/**
	 * @return the context
	 */
	public EncogProgramContext getContext() {
		return context;
	}

	public void calculateEffectiveScore(Genome theGenome) {
		EncogProgram prg = (EncogProgram)theGenome;
		GeneticTrainingParams params = this.context.getParams();
		double result = prg.getScore();
		if (prg.size() > params.getComplexityPenaltyThreshold()) {
			int over = prg.size() - params.getComplexityPenaltyThreshold();
			int range = params.getComplexityPentaltyFullThreshold()
					- params.getComplexityPenaltyThreshold();
			double complexityPenalty = ((params.getComplexityFullPenalty() - params
					.getComplexityPenalty()) / range) * over;
			result += (result * complexityPenalty);
		}
		prg.setAdjustedScore(result);
	}

	/**
	 * @return the selector
	 */
	public ThreadedGenomeSelector getSelector() {
		return selector;
	}

	/**
	 * @return the operators
	 */
	public OperationList getOperators() {
		return operators;
	}
	
	

}
