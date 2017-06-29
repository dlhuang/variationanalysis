package org.campagnelab.dl.genotype.learning.domains;

import org.apache.commons.compress.utils.IOUtils;
import org.campagnelab.dl.framework.architecture.graphs.ComputationGraphAssembler;
import org.campagnelab.dl.framework.domains.DomainDescriptor;
import org.campagnelab.dl.framework.domains.prediction.Prediction;
import org.campagnelab.dl.framework.domains.prediction.PredictionInterpreter;
import org.campagnelab.dl.framework.mappers.BooleanLabelMapper;
import org.campagnelab.dl.framework.mappers.ConfigurableFeatureMapper;
import org.campagnelab.dl.framework.mappers.FeatureMapper;
import org.campagnelab.dl.framework.mappers.LabelMapper;
import org.campagnelab.dl.framework.performance.PerformanceMetricDescriptor;
import org.campagnelab.dl.genotype.learning.GenotypeTrainingArguments;
import org.campagnelab.dl.genotype.learning.architecture.graphs.*;
import org.campagnelab.dl.genotype.learning.domains.predictions.*;
import org.campagnelab.dl.genotype.mappers.*;
import org.campagnelab.dl.genotype.performance.AlleleAccuracyHelper;
import org.campagnelab.dl.genotype.performance.GenotypeTrainingPerformanceHelper;
import org.campagnelab.dl.genotype.performance.GenotypeTrainingPerformanceHelperWithAUC;
import org.campagnelab.dl.genotype.predictions.*;
import org.campagnelab.dl.somatic.learning.TrainSomaticModel;
import org.campagnelab.dl.somatic.learning.iterators.BaseInformationConcatIterator;
import org.campagnelab.dl.somatic.learning.iterators.BaseInformationIterator;
import org.campagnelab.dl.varanalysis.protobuf.BaseInformationRecords;
import org.campagnelab.goby.baseinfo.SequenceBaseInformationReader;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.api.iterator.MultiDataSetIterator;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.ILossFunction;
import org.nd4j.linalg.lossfunctions.impl.LossBinaryXENT;
import org.nd4j.linalg.lossfunctions.impl.LossMCXENT;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class GenotypeDomainDescriptor extends DomainDescriptor<BaseInformationRecords.BaseInformation> {


    private final boolean isLstmIndelModel;
    private final boolean isLstmIndelAggregateModel;
    private boolean addTrueGenotypeLabels;
    private int ploidy;
    private double decisionThreshold;
    double variantLossWeight;
    private int genomicContextSize;
    private int indelSequenceLength;
    private int trueGenotypeLength;
    private float modelCapacity;
    private boolean isPredicting;


    public GenotypeDomainDescriptor(GenotypeTrainingArguments arguments) {
        this.arguments = arguments;
        initializeArchitecture(arguments.architectureClassname);
        this.ploidy = arguments.ploidy;
        this.decisionThreshold = arguments.decisionThreshold;
        variantLossWeight = args().variantLossWeight;
        genomicContextSize = args().genomicContextLength;
        isLstmIndelModel = netArchitectureHasIndelLSTM(args().architectureClassname);
        isLstmIndelAggregateModel = netArchitectureHasIndelAggregateLSTM(args().architectureClassname);
        indelSequenceLength = args().indelSequenceLength;
        trueGenotypeLength = args().trueGenotypeLength;
        modelCapacity = args().modelCapacity;
        addTrueGenotypeLabels = args().addTrueGenotypeLabels;
        if (modelCapacity < 0) {
            throw new RuntimeException("Model capacity cannot be negative. Typical values are >=1 (1-5)");
        }
    }

    /**
     * Use this method to create a domain descriptor for a trained model.
     *
     * @param modelPath Path where the model is stored.
     */
    public GenotypeDomainDescriptor(String modelPath) {

        this.arguments = new GenotypeTrainingArguments();
        super.loadProperties(modelPath);
        // force loading the feature mappers from properties.
        args().featureMapperClassname = null;
        isLstmIndelModel = netArchitectureHasIndelLSTM(domainProperties.getProperty("net.architecture.classname"));
        isLstmIndelAggregateModel = netArchitectureHasIndelAggregateLSTM(domainProperties.getProperty("net.architecture.classname"));
        addTrueGenotypeLabels = Boolean.parseBoolean(domainProperties.getProperty("addTrueGenotypeLabels"));
        configure(modelProperties);
        initializeArchitecture();
    }


    @Override
    public void putProperties(Properties props) {
        super.putProperties(props);
        props.setProperty(NumDistinctAllelesLabelMapper.PLOIDY_PROPERTY, Integer.toString(ploidy));
        props.setProperty(GenotypePrediction.DECISION_THRESHOLD_PROPERTY, Double.toString(decisionThreshold));
        decorateProperties(props);
    }

    /**
     * Use this methdo to create a domain before training. The supplied properties provide
     * featureMapper and labelMapper information (domainProperties), and the sbiProperties
     * provide statistic observed on the training set (or part of it).
     *
     * @param domainProperties Properties describing the domain. Must describe feature and label mappers.
     * @param sbiProperties    Properties describing statistics, used to configure feature mappers.
     */
    public GenotypeDomainDescriptor(Properties domainProperties, Properties sbiProperties) {

        this.arguments = new GenotypeTrainingArguments();
        super.loadProperties(domainProperties, sbiProperties);
        // force loading the feature mappers from properties.
        args().featureMapperClassname = null;
        isLstmIndelModel = netArchitectureHasIndelLSTM(domainProperties.getProperty("net.architecture.classname"));
        isLstmIndelAggregateModel = netArchitectureHasIndelAggregateLSTM(domainProperties.getProperty("net.architecture.classname"));
        addTrueGenotypeLabels = Boolean.parseBoolean(domainProperties.getProperty("addTrueGenotypeLabels"));
        configure(modelProperties);
        initializeArchitecture();
    }

    private GenotypeTrainingArguments arguments;

    private GenotypeTrainingArguments args() {
        return arguments;
    }

    Map<String, FeatureMapper> featureMappers = new HashMap<>();

    private boolean isLSTMInput(String inputName) {
        return "from".equals(inputName) || "G1".equals(inputName) || "G2".equals(inputName) || "G3".equals(inputName);
    }

    private boolean netArchitectureHasIndelLSTM(String className) {
        return className.endsWith("GenotypeSixDenseLayersWithIndelLSTM");
    }

    private boolean netArchitectureHasIndelAggregateLSTM(String classname) {
        return classname.endsWith("GenotypeSixDenseLayersWithIndelLSTMAggregate");
    }

    @Override
    public FeatureMapper getFeatureMapper(String inputName, boolean isPredicting) {
        if (featureMappers.containsKey(inputName)) {
            return featureMappers.get(inputName);
        }
        if (inputName.equals("trueGenotypeInput") && isPredicting) {
            TrueGenotypeLSTMDecodingFeatureMapper glpfMapper = new TrueGenotypeLSTMDecodingFeatureMapper();
            Properties glpfMapperProperties = new Properties();
            glpfMapperProperties.setProperty("isPredicting", "true");
            decorateProperties(glpfMapperProperties);
            glpfMapper.configure(glpfMapperProperties);
            return glpfMapper;
        } else {
            return getFeatureMapper(inputName);
        }
    }

    @Override
    public FeatureMapper getFeatureMapper(String inputName) {
        if (featureMappers.containsKey(inputName)) {
            return featureMappers.get(inputName);
        }
        FeatureMapper result;

        if (isLSTMInput(inputName)) {
            result = new GenotypeMapperLSTM();
            GenotypeMapperLSTM glMapper = (GenotypeMapperLSTM) result;
            Properties glMapperProperties = new Properties();
            decorateProperties(glMapperProperties);
            glMapper.configure(glMapperProperties);
            switch (inputName) {
                case "from":
                    glMapper.setInputType(GenotypeMapperLSTM.Input.FROM);
                    break;
                case "G1":
                    glMapper.setInputType(GenotypeMapperLSTM.Input.G1);
                    break;
                case "G2":
                    glMapper.setInputType(GenotypeMapperLSTM.Input.G2);
                    break;
                case "G3":
                    glMapper.setInputType(GenotypeMapperLSTM.Input.G3);
                    break;
                default:
                    throw new RuntimeException("Invalid LSTM mapper input name");
            }
        } else if (inputName.equals("indel")) {
            result = new GenotypeMapperLSTMAllStrands();
            GenotypeMapperLSTMAllStrands glaMapper = (GenotypeMapperLSTMAllStrands) result;
            Properties glMapperProperties = new Properties();
            decorateProperties(glMapperProperties);
            glaMapper.configure(glMapperProperties);
        } else if (inputName.equals("trueGenotypeInput")) {
            result = new TrueGenotypeLSTMDecodingFeatureMapper();
            TrueGenotypeLSTMDecodingFeatureMapper glpfMapper = (TrueGenotypeLSTMDecodingFeatureMapper) result;
            Properties glpfMapperProperties = new Properties();
            decorateProperties(glpfMapperProperties);
            glpfMapper.configure(glpfMapperProperties);
        } else if (args().featureMapperClassname != null) {
            assert "input".equals(inputName) : "Only one input supported by this domain.";
            try {
                Class clazz = Class.forName(args().featureMapperClassname);
                final FeatureMapper featureMapper = (FeatureMapper) clazz.newInstance();
                if (featureMapper instanceof ConfigurableFeatureMapper) {
                    ConfigurableFeatureMapper cmapper = (ConfigurableFeatureMapper) featureMapper;
                    final Properties properties = TrainSomaticModel.getReaderProperties(args().trainingSets.get(0));
                    decorateProperties(properties);
                    cmapper.configure(properties);
                }
                result = featureMapper;
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("Unable to instantiate or configure feature mapper", e);
            } catch (IOException | IllegalAccessException | InstantiationException e) {
                throw new RuntimeException("IO excpetion, perhaps sbi file not found?", e);
            }
        } else {
            try {
                String mapperName = domainProperties.getProperty("input.featureMapper");

                FeatureMapper fMapper = (FeatureMapper) Class.forName(mapperName).newInstance();

                if (fMapper instanceof ConfigurableFeatureMapper) {
                    ConfigurableFeatureMapper cfmapper = (ConfigurableFeatureMapper) fMapper;
                    decorateProperties(modelProperties);
                    cfmapper.configure(modelProperties);
                }
                result = fMapper;

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        featureMappers.put(inputName, result);
        return result;
    }

    @Override
    public LabelMapper getLabelMapper(String outputName) {
        boolean sortCounts = needSortCounts();

        switch (outputName) {
            case "A":
                return new SingleGenotypeLabelMapper(0, sortCounts, args().labelSmoothingEpsilon);
            case "T":
                return new SingleGenotypeLabelMapper(1, sortCounts, args().labelSmoothingEpsilon);
            case "C":
                return new SingleGenotypeLabelMapper(2, sortCounts, args().labelSmoothingEpsilon);
            case "G":
                return new SingleGenotypeLabelMapper(3, sortCounts, args().labelSmoothingEpsilon);
            case "N":
                return new SingleGenotypeLabelMapper(4, sortCounts, args().labelSmoothingEpsilon);
            case "I1":
                return new SingleGenotypeLabelMapper(5, sortCounts, args().labelSmoothingEpsilon);
            case "I2":
                return new SingleGenotypeLabelMapper(6, sortCounts, args().labelSmoothingEpsilon);
            case "I3":
                return new SingleGenotypeLabelMapper(7, sortCounts, args().labelSmoothingEpsilon);
            case "I4":
                return new SingleGenotypeLabelMapper(8, sortCounts, args().labelSmoothingEpsilon);
            case "I5":
                return new SingleGenotypeLabelMapper(9, sortCounts, args().labelSmoothingEpsilon);
            case "homozygous":
                return new HomozygousLabelsMapper(sortCounts, args().labelSmoothingEpsilon);
            case "numDistinctAlleles":
                return new NumDistinctAllelesLabelMapper(sortCounts, ploidy, args().labelSmoothingEpsilon);
            case "combined":
                return new CombinedLabelsMapper(args().labelSmoothingEpsilon);
            case "combinedRef":
                return new CombinedLabelsMapperRef(args().labelSmoothingEpsilon);
            case "metaData":
                return new MetaDataLabelMapper();
            case "isVariant":
                return new BooleanLabelMapper<BaseInformationRecords.BaseInformation>(
                        baseInformation -> baseInformation.getSamples(0).getIsVariant(),
                        args().labelSmoothingEpsilon);
            case "trueGenotype":
                return new TrueGenotypeLSTMLabelMapper(args().trueGenotypeLength);
            default:
                throw new IllegalArgumentException("output name is not recognized: " + outputName);
        }

    }

    private boolean needSortCounts() {
        return ((GenotypeFeatureMapper) getFeatureMapper("input")).sortCounts;
    }

    private boolean withDistinctAllele() {
        final GenotypeFeatureMapper featureMapper = (GenotypeFeatureMapper) getFeatureMapper("input");
        return featureMapper.withDistinctAlleleCounts;
    }

    private boolean withCombinedLayer() {
        final GenotypeFeatureMapper featureMapper = (GenotypeFeatureMapper) getFeatureMapper("input");
        return featureMapper.withCombinedLayer;
    }

    private boolean withCombinedLayerRef() {
        final GenotypeFeatureMapper featureMapper = (GenotypeFeatureMapper) getFeatureMapper("input");
        return featureMapper.withCombinedLayerRef;
    }

    private boolean withIsVariantLabelMapper() {
        return ((GenotypeFeatureMapper) getFeatureMapper("input")).hasIsVariantLabelMapper;
    }

    @Override
    public void configure(Properties modelProperties) {
        super.configure(modelProperties);
        String property = modelProperties.getProperty(NumDistinctAllelesLabelMapper.PLOIDY_PROPERTY);
        if (property == null) {
            throw new RuntimeException(String.format("property %s must be found in model config.properties",
                    NumDistinctAllelesLabelMapper.PLOIDY_PROPERTY));
        }
        ploidy = Integer.parseInt(property);

        property = modelProperties.getProperty(GenotypePrediction.DECISION_THRESHOLD_PROPERTY);
        if (property == null) {
            decisionThreshold = 0.5d;
        } else {
            decisionThreshold = Double.parseDouble(property);
        }

        String variantLossWeightProperty = modelProperties.getProperty("variantLossWeight");
        variantLossWeight = Double.parseDouble(variantLossWeightProperty);
        String modelCapacityProperty = modelProperties.getProperty("modelCapacity");
        modelCapacity = Float.parseFloat(modelCapacityProperty);
        indelSequenceLength = Integer.parseInt(modelProperties.getProperty("indelSequenceLength"));
        trueGenotypeLength = Integer.parseInt(modelProperties.getProperty("trueGenotypeLength"));
        addTrueGenotypeLabels = Boolean.parseBoolean(modelProperties.getProperty("addTrueGenotypeLabels"));

    }

    @Override
    public String produceCacheUniqueId(int miniBatchSize) {
        String id = super.produceCacheUniqueId(miniBatchSize);
        int domainHashcode = id.hashCode();
        domainHashcode ^= ploidy;
        domainHashcode ^= genomicContextSize;
        domainHashcode ^= indelSequenceLength;
        domainHashcode ^= Float.hashCode(args().labelSmoothingEpsilon);
        return Integer.toHexString(domainHashcode);
    }

    /**
     * Record arguments to the properties, that need to be provided to feature/label mappers.
     *
     * @param modelProperties
     */
    void decorateProperties(Properties modelProperties) {
        if (args().genomicContextLength != Integer.MAX_VALUE) {
            // override the .sbi context size only if the argument was used on the command line:
            genomicContextSize = args().genomicContextLength;
            modelProperties.setProperty("stats.genomicContextSize.min", Integer.toString(args().genomicContextLength));
            modelProperties.setProperty("stats.genomicContextSize.max", Integer.toString(args().genomicContextLength));
        }
        if (args().indelSequenceLength != args().defaultIndelSequenceLength()) {
            // override the .sbi context size only if the argument was used on the command line:
            indelSequenceLength = args().indelSequenceLength;
            modelProperties.setProperty("indelSequenceLength", Integer.toString(args().indelSequenceLength));
        }
        modelProperties.setProperty("modelCapacity", Float.toString(args().modelCapacity));
        modelProperties.setProperty("variantLossWeight", Double.toString(variantLossWeight));
        modelProperties.setProperty("labelSmoothing.epsilon", Double.toString(args().labelSmoothingEpsilon));
        modelProperties.setProperty("indelSequenceLength", Integer.toString(args().indelSequenceLength));
        modelProperties.setProperty("trueGenotypeLength", Integer.toString(args().trueGenotypeLength));
        modelProperties.setProperty("addTrueGenotypeLabels", Boolean.toString(args().addTrueGenotypeLabels));
    }

    @Override
    public PredictionInterpreter getPredictionInterpreter(String outputName) {
        boolean sortCounts = needSortCounts();
        boolean needCombine = withCombinedLayer();
        switch (outputName) {
            case "A":
                return new SingleGenotypeInterpreter(0, sortCounts);
            case "T":
                return new SingleGenotypeInterpreter(1, sortCounts);
            case "C":
                return new SingleGenotypeInterpreter(2, sortCounts);
            case "G":
                return new SingleGenotypeInterpreter(3, sortCounts);
            case "N":
                return new SingleGenotypeInterpreter(4, sortCounts);
            case "I1":
                return new SingleGenotypeInterpreter(5, sortCounts);
            case "I2":
                return new SingleGenotypeInterpreter(6, sortCounts);
            case "I3":
                return new SingleGenotypeInterpreter(7, sortCounts);
            case "I4":
                return new SingleGenotypeInterpreter(8, sortCounts);
            case "I5":
                return new SingleGenotypeInterpreter(9, sortCounts);
            //only need one interpreter for each record, it will collect entire genotype into a prediction
            case "homozygous":
                return new HomozygousInterpreter(sortCounts);
            case "combined":
                return new CombinedOutputLayerInterpreter();
            case "combinedRef":
                return new CombinedOutputLayerRefInterpreter();
            case "numDistinctAlleles":
                return new NumDistinctAllelesInterpreter(ploidy);
            case "isVariant":
                return new IsVariantInterpreter(decisionThreshold);
            case "metaData":
                return new MetaDataInterpreter();
            case "trueGenotype":
                return new TrueGenotypeOutputLayerInterpreter();
            default:
                throw new IllegalArgumentException("output name is not recognized: " + outputName);
        }


    }

    @Override
    public Function<String, ? extends Iterable<BaseInformationRecords.BaseInformation>> getRecordIterable() {
        return inputFilename -> {
            try {
                return new SequenceBaseInformationReader(inputFilename);
            } catch (IOException e) {
                throw new RuntimeException("Unable to read records from " + inputFilename, e);
            }
        };
    }

    @Override
    public PerformanceMetricDescriptor<BaseInformationRecords.BaseInformation> performanceDescritor() {
        return new PerformanceMetricDescriptor<BaseInformationRecords.BaseInformation>(this) {


            @Override
            public String[] performanceMetrics() {
                return new String[]{"AUC", "Recall", "Precision", "F1", "NumVariants", "score", "Recall_Indels", "Precision_Indels", "F1_Indels", "numIndels", "Het_Hom_Ratio", "TP", "TN"};
                //       return new String[]{"AUC_V", "AUC_R", "Concordance", "Recall", "Precision", "F1", "NumVariants", "score", "AUC_VxR"};
            }

            @Override
            public boolean largerValueIsBetterPerformance(String metricName) {
                switch (metricName) {

                    case "score":
                        return false;
                    case "AUC_V":
                    case "AUC":
                    case "AUC_R":
                    case "F1":
                    case "F1_SNPs":
                    case "F1_Indels":
                    case "Recall_Indels":
                    case "Precision_Indels":
                    case "AUC_VxR":
                    case "AUC+F1":
                    case "accuracy":
                    case "Recall":
                    case "Precision":
                    case "alleleAccuracy":
                    case "NumVariants":
                    case "numIndels":
                    case "Het_Hom_Ratio":
                    case "Het":
                    case "Hom":
                    case "Concordance":
                    case "TP":
                    case "TN":
                        return true;
                    default:
                        throw new IllegalArgumentException("metric not recognized: " + metricName);
                }
            }

            @Override
            public double estimateMetric(ComputationGraph graph, String metricName, MultiDataSetIterator dataSetIterator, long scoreN) {
                switch (metricName) {
                    case "Accuracy":
                    case "AUC":
                    case "AUC_V":
                    case "AUC_R":
                    case "F1":
                    case "F1_Indels":
                    case "F1_SNPs":
                    case "AUC+F1":
                    case "AUC_VxR":
                    case "Recall":
                    case "Precision":
                    case "NumVariants":
                    case "Concordance":
                        assert false : "avoid using the single metric evaluation method. Very slow.";
                        GenotypeTrainingPerformanceHelper helper = new GenotypeTrainingPerformanceHelper(domainDescriptor);
                        return helper.estimateWithGraph(dataSetIterator, graph,
                                index -> index > scoreN);
                    case "alleleAccuracy":
                        AlleleAccuracyHelper alleleHelper = new AlleleAccuracyHelper();
                        return alleleHelper.estimateWithGraph(dataSetIterator, graph,
                                index -> index > scoreN
                            /* first output represents probabilityIsCalled of mutation */);
                    default:
                        return estimateScore(graph, metricName, dataSetIterator, scoreN);
                }
            }

            @Override
            public double[] estimateMetric(ComputationGraph graph, MultiDataSetIterator dataSetIterator, long scoreN,
                                           String... metrics) {


                GenotypeTrainingPerformanceHelperWithAUC helper = new GenotypeTrainingPerformanceHelperWithAUC(domainDescriptor);
                helper.estimateWithGraph(dataSetIterator, graph,
                        index -> index > scoreN
                            /* first output represents probabilityIsCalled of mutation */);
                return helper.getMetricValues(metrics);
            }

            @Override
            public String earlyStoppingMetric() {
                return args().earlyStoppingMeasureName;
            }
        };
    }

    @Override
    public ComputationGraphAssembler getComputationalGraph() {
        ComputationGraphAssembler assembler;
        if (withDistinctAllele()) {
            if (isLstmIndelModel) {
                assembler = new GenotypeSixDenseLayersWithIndelLSTM(GenotypeSixDenseLayersWithIndelLSTM.OutputType.DISTINCT_ALLELES,
                        withIsVariantLabelMapper(), withCombinedLayerRef(), addTrueGenotypeLabels);
            } else if (isLstmIndelAggregateModel) {
                assembler = new GenotypeSixDenseLayersWithIndelLSTMAggregate(GenotypeSixDenseLayersWithIndelLSTMAggregate.OutputType.DISTINCT_ALLELES,
                        withIsVariantLabelMapper(), withCombinedLayerRef(), addTrueGenotypeLabels);
            } else {
                assembler = new NumDistinctAlleleAssembler(withIsVariantLabelMapper());
            }
        } else if (withCombinedLayer()) {
            if (isLstmIndelModel) {
                assembler = new GenotypeSixDenseLayersWithIndelLSTM(GenotypeSixDenseLayersWithIndelLSTM.OutputType.COMBINED,
                        withIsVariantLabelMapper(), withCombinedLayerRef(), addTrueGenotypeLabels);
            } else if (isLstmIndelAggregateModel) {
                assembler = new GenotypeSixDenseLayersWithIndelLSTMAggregate(GenotypeSixDenseLayersWithIndelLSTMAggregate.OutputType.COMBINED,
                        withIsVariantLabelMapper(), withCombinedLayerRef(), addTrueGenotypeLabels);
            } else {
                assembler = new CombinedGenotypeAssembler(withIsVariantLabelMapper(), withCombinedLayerRef());
            }
        } else if (!withDistinctAllele() && !withCombinedLayer()) {
            if (isLstmIndelModel) {
                assembler = new GenotypeSixDenseLayersWithIndelLSTM(GenotypeSixDenseLayersWithIndelLSTM.OutputType.HOMOZYGOUS,
                        withIsVariantLabelMapper(), withCombinedLayerRef(), addTrueGenotypeLabels);
            } else if (isLstmIndelAggregateModel) {
                assembler = new GenotypeSixDenseLayersWithIndelLSTMAggregate(GenotypeSixDenseLayersWithIndelLSTMAggregate.OutputType.HOMOZYGOUS,
                        withIsVariantLabelMapper(), withCombinedLayerRef(), addTrueGenotypeLabels);
            } else {
                assembler = new GenotypeSixDenseLayersNarrower2(withIsVariantLabelMapper());
            }
        } else {
            try {
                assembler = (ComputationGraphAssembler) Class.forName(args().architectureClassname).newInstance();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        assembler.setArguments(args());
        return assembler;
    }

    @Override
    public int[] getNumInputs(String inputName) {
        return getFeatureMapper(inputName).dimensions().dimensions;
    }

    @Override
    public int[] getNumOutputs(String outputName) {
        return getLabelMapper(outputName).dimensions().dimensions;
    }

    @Override
    public int[] getNumMaskInputs(String inputName) {
        // For 2D feature mappers, need 2nd dimension, as this corresponds to the number of time steps
        // For 1D feature mappers, 1st dimension is just the number of features
        int idx = getNumInputs(inputName).length == 1 ? 1 : 2;
        return new int[]{getFeatureMapper(inputName).dimensions().numElements(idx)};
    }

    @Override
    public int[] getNumMaskOutputs(String outputName) {
        // For 2D feature mappers, need 2nd dimension, as this corresponds to the number of time steps
        // For 1D feature mappers, 1st dimension is just the number of features
        int idx = getNumOutputs(outputName).length == 1 ? 1 : 2;
        return new int[]{getLabelMapper(outputName).dimensions().numElements(idx)};
    }

    @Override
    public int getNumHiddenNodes(String componentName) {
        switch (componentName) {
            case "lstmIndelLayer":
                return args().numLSTMHiddenNodesIndels;
            case "lstmTrueGenotypeLayer":
                return args().numLSTMHiddenNodesTrueGenotype;
            default:
                return Math.round(getNumInputs("input")[0] * modelCapacity);
        }
    }

    @Override
    public ILossFunction getOutputLoss(String outputName) {
        switch (outputName) {
            case "homozygous":
            case "numDistinctAlleles":
            case "combined":
                return new LossMCXENT();
            case "combinedRef":
                return new LossMCXENT();
            case "isVariant":
                INDArray weights = Nd4j.ones(2);

                weights.putScalar(BooleanLabelMapper.IS_TRUE, variantLossWeight);
                weights.putScalar(BooleanLabelMapper.IS_FALSE, 0);
                return new LossBinaryXENT(weights);
            // return new LossBinaryXENT();
            case "metaData":
                // no loss for metaData. These labels are virtual.
                INDArray zeros = Nd4j.zeros(MetaDataLabelMapper.NUM_LABELS);
                return new LossBinaryXENT(zeros);
            case "trueGenotype":
                return new LossMCXENT();
            default:
                // any other is an individual genotype output:
                return new LossBinaryXENT();
        }
    }

    @Override
    public GenotypePrediction aggregatePredictions(BaseInformationRecords.BaseInformation record, List<Prediction> individualOutputPredictions) {
        if (addTrueGenotypeLabels) {
            return new TrueGenotypePrediction(record, individualOutputPredictions, withDistinctAllele(),
                    withCombinedLayer(), withIsVariantLabelMapper(), decisionThreshold);
        } else if (withDistinctAllele()) {
            if (withIsVariantLabelMapper()) {
                return new NumDistinctAlleleWithIsVariantGenotypePrediction(record, decisionThreshold, individualOutputPredictions);
            } else {
                return new NumDistinctAlleleGenotypePrediction(record, decisionThreshold, individualOutputPredictions);
            }
        } else if (withCombinedLayer()) {
            if (withIsVariantLabelMapper()) {
                return new CombinedWithIsVariantGenotypePrediction(individualOutputPredictions);
            } else {
                return new CombinedGenotypePrediction(individualOutputPredictions);
            }
        }
        throw new IllegalArgumentException("The type of aggregate prediction is not recognized.");
    }

    @Override
    public long getNumRecords(String[] recordFiles) {
        BaseInformationConcatIterator it = null;
        try {
            List<BaseInformationIterator> list = Arrays.asList(recordFiles).stream().map(filename -> {
                try {

                    return new BaseInformationIterator(filename, 128, featureMappers()[0], getLabelMapper("A"));
                } catch (IOException e) {
                    throw new RuntimeException("Unable to estimate number of records for filename " + filename);
                }
            }).collect(Collectors.toList());
            it = new BaseInformationConcatIterator(list, 128, featureMappers()[0], getLabelMapper("A"));
            return it.totalExamples();

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            IOUtils.closeQuietly(it);
        }
        return 0;
    }
}