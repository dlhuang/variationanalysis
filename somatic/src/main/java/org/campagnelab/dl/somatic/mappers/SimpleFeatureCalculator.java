package org.campagnelab.dl.somatic.mappers;

import org.campagnelab.dl.framework.mappers.FeatureCalculator;
import org.campagnelab.dl.somatic.genotypes.BaseGenotypeCountFactory;
import org.campagnelab.dl.somatic.genotypes.GenotypeCountFactory;
import org.campagnelab.dl.varanalysis.protobuf.BaseInformationRecords;
import org.nd4j.linalg.api.ndarray.INDArray;

/**
 * This is a simple feature mapper. It is designed using information currently available in the Parquet file.
 * Each position has the following information:
 * <pre> 69033640	11	false
 * position=14521   referenceIndex=0       isMutated=false
 * sample 0 counts=[10, 0, 0, 63, 0, 4, 0, 1, 62, 0]
 * sample 1 counts=[2, 0, 0, 45, 0, 3, 0, 0, 68, 0]
 *
 * position=14521   referenceIndex=0       isMutated=true
 * sample 0 counts=[10, 0, 0, 63, 0, 4, 0, 1, 62, 0]
 * sample 1 counts=[2, 11, 0, 34, 0, 3, 12, 0, 56, 0]
 * </pre>
 * <p>
 * Using these data, we can map to features as follows:
 * <ul>
 * <li>Make the isMutated boolean the only label. This is not ideal: the net may tell us if the base is mutated,
 * but we will not know what the mutation is..</li>
 * <li>Concatenate the count integers and use these as features. The only way for the net to learn from these data is to count
 * the number of counts elements that has "enough" reads to call a genotype. If the genotype calls are more than two, then
 * the site is likely mutated, because most sites will be heterozygous at most. With these features, I expect the
 * net to have more trouble predicting mutations at homozygous sites, than at heterozygous sites. We'll see. </ul>
 * Created by fac2003 on 5/21/16.
 *
 * @author Fabien Campagne
 */

public class SimpleFeatureCalculator extends AbstractFeatureMapper<BaseInformationRecords.BaseInformationOrBuilder>

        implements FeatureCalculator<BaseInformationRecords.BaseInformationOrBuilder> {


    public SimpleFeatureCalculator(boolean sort) {
        this.sort = sort;

    }

    public SimpleFeatureCalculator() {
        this.sort = true;
    }

    boolean normalized = false;


    @Override
    public int numberOfFeatures() {
        // we need features for the normal sample and for the tumor sample:

        return AbstractFeatureMapper.MAX_GENOTYPES * 2 * 2;
    }

    boolean sort; //by default we sort
    int sumCounts;

    public void prepareToNormalize(BaseInformationRecords.BaseInformationOrBuilder record, int indexOfRecord) {
        indices[0] = indexOfRecord;

        sumCounts = 0;
        for (int featureIndex = 0; featureIndex < numberOfFeatures(); featureIndex++) {
            sumCounts += produceFeatureInternal(record, featureIndex);
        }
        normalized = true;
    }

    @Override
    public int numberOfLabels() {
        return 2;
    }


    int[] indices = new int[]{0, 0};

    @Override
    public void mapFeatures(BaseInformationRecords.BaseInformationOrBuilder record, INDArray inputs, int indexOfRecord) {
        assert normalized : "prepareToNormalize must be called before mapFeatures.";
        indices[0] = indexOfRecord;

        final float[] buffer = getBuffer();
        mapFeatures(record, buffer, 0, indexOfRecord);
        for (int featureIndex = 0; featureIndex < numberOfFeatures(); featureIndex++) {
            indices[1] = featureIndex;
            inputs.putScalar(indices, buffer[featureIndex]);
        }
    }


    public void mapFeatures(BaseInformationRecords.BaseInformationOrBuilder record, float[] inputs, int offset, int indexOfRecord) {
        assert normalized : "prepareToNormalize must be called before mapFeatures.";
        for (int featureIndex = 0; featureIndex < numberOfFeatures(); featureIndex++) {
            inputs[featureIndex + offset] = produceFeature(record, featureIndex);
        }
    }

    public float produceFeature(BaseInformationRecords.BaseInformationOrBuilder record, int featureIndex) {
        return normalize(produceFeatureInternal(record, featureIndex), sumCounts);
    }

    @Override
    public String getFeatureName(int featureIndex) {
        assert (featureIndex >= 0 && featureIndex < AbstractFeatureMapper.MAX_GENOTYPES * 2 * 2) : "Only MAX_GENOTYPES*2*2 features";
        if (featureIndex < AbstractFeatureMapper.MAX_GENOTYPES * 2) {
            // germline counts written first:
            if ((featureIndex % 2) == 1) {
                // odd featureIndices are forward strand:
                return ("normGermlineForwardCount" + (featureIndex / 2));
            } else {
                return ("normGermlineReverseCount" + (featureIndex / 2));
            }
        } else {
            // tumor counts written next:
            featureIndex -= AbstractFeatureMapper.MAX_GENOTYPES * 2;
            if ((featureIndex % 2) == 1) {
                // odd featureIndices are forward strand:
                return ("normSomaticForwardCount" + (featureIndex / 2));
            } else {
                return ("normGomaticReverseCount" + (featureIndex / 2));
            }
        }
    }

    @Override
    public void mapLabels(BaseInformationRecords.BaseInformationOrBuilder record, INDArray labels, int indexOfRecord) {
        indices[0] = indexOfRecord;

        for (int labelIndex = 0; labelIndex < numberOfLabels(); labelIndex++) {
            indices[1] = labelIndex;
            labels.putScalar(indices, produceLabel(record, labelIndex));
        }
    }

    private float normalize(float value, int normalizationFactor) {
        if (normalizationFactor == 0) {
            return 0;
        }
        float normalized = value / normalizationFactor;
        assert normalized >= 0 && normalized <= 1 : "value must be normalized: " + normalized;
        return normalized;
    }


    public float produceFeatureInternal(BaseInformationRecords.BaseInformationOrBuilder record, int featureIndex) {
        assert (featureIndex >= 0 && featureIndex < AbstractFeatureMapper.MAX_GENOTYPES * 2 * 2) : "Only MAX_GENOTYPES*2*2 features";
        if (featureIndex < AbstractFeatureMapper.MAX_GENOTYPES * 2) {
            // germline counts written first:
            if ((featureIndex % 2) == 1) {
                // odd featureIndices are forward strand:
                return getAllCounts(record, false, sort).get(featureIndex / 2).forwardCount;
            } else {
                return getAllCounts(record, false, sort).get(featureIndex / 2).reverseCount;
            }
        } else {
            // tumor counts written next:
            featureIndex -= AbstractFeatureMapper.MAX_GENOTYPES * 2;
            if ((featureIndex % 2) == 1) {
                // odd featureIndices are forward strand:
                return getAllCounts(record, true, sort).get(featureIndex / 2).forwardCount;
            } else {
                return getAllCounts(record, true, sort).get(featureIndex / 2).reverseCount;
            }
        }
    }


    @Override
    public float produceLabel(BaseInformationRecords.BaseInformationOrBuilder record, int labelIndex) {
        assert labelIndex == 0 || labelIndex == 1 : "only one label.";

        //return record.getMutated() ? 1.0f : 0.0f;
        if (labelIndex == 0) return record.getMutated() ? 1 : 0;
        else {
            return !record.getMutated() ? 1 : 0;
        }

    }

    @Override
    protected void initializeCount(BaseInformationRecords.CountInfo sampleCounts, GenotypeCount count) {
        // nothing to do, already done in the base class.
    }

    @Override
    protected GenotypeCountFactory getGenotypeCountFactory() {

        return new BaseGenotypeCountFactory();

    }
}
