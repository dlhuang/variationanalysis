package org.campagnelab.dl.somatic.learning.architecture;

import org.campagnelab.dl.framework.architecture.nets.NeuralNetAssembler;
import org.deeplearning4j.nn.conf.LearningRatePolicy;
import org.deeplearning4j.nn.weights.WeightInit;
import org.nd4j.linalg.lossfunctions.LossFunctions;

/**
 * Created by fac2003 on 6/10/16.
 */
public abstract class AbstractNeuralNetAssembler implements NeuralNetAssembler {
    protected double learningRate;
    protected long seed;
    protected int numInputs;
    protected int numOutputs;
    protected int numHiddenNodes;
    protected boolean regularization = false;
    protected double regularizationRate = 0.00002;
    protected LearningRatePolicy learningRatePolicy = LearningRatePolicy.None;
    boolean dropOut;
    protected double dropOutRate;
    protected LossFunctions.LossFunction lossFunction = LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD;

    public void setLearningRatePolicy(LearningRatePolicy learningRatePolicy) {
        this.learningRatePolicy = learningRatePolicy;
    }

    public void setWeightInitialization(WeightInit init) {
        this.WEIGHT_INIT = init;
    }

    public WeightInit WEIGHT_INIT = WeightInit.XAVIER;

    public void setLearningRate(double learningRate) {
        this.learningRate = learningRate;
    }

    public void setSeed(long seed) {
        this.seed = seed;
    }

    public void setNumInputs(int numInputs) {
        this.numInputs = numInputs;
    }

    public void setNumOutputs(int numOutputs) {
        this.numOutputs = numOutputs;
    }

    public void setNumHiddenNodes(int numHiddenNodes) {
        this.numHiddenNodes = numHiddenNodes;
    }

    public void setRegularization(boolean regularization) {
        this.regularization = regularization;
    }

    public void setRegularizationRate(Double regularizationRate) {
        if (regularizationRate != null) {
            this.regularizationRate = regularizationRate;
        }
    }

    @Override
    public void setDropoutRate(Double rate) {
        if (rate != null) {
            this.dropOut = true;
            this.dropOutRate = rate;
        }
    }

    public void setLossFunction(LossFunctions.LossFunction lossFunction) {
        this.lossFunction = lossFunction;
    }
}
