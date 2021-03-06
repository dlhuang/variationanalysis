package org.campagnelab.dl.framework.mappers;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import org.nd4j.linalg.api.ndarray.INDArray;

import java.util.Arrays;

/**
 * Concatenate features from different mappers.
 * Created by fac2003 on 5/24/16.
 */
public class ConcatFeatureMapper<RecordType> implements FeatureMapper<RecordType> {

    protected FeatureMapper<RecordType>[] mappers;
    protected int numFeatures = 0;
    protected int[] offsets;
    private boolean normalizedCalled;
    private boolean requiresMask;

    @SafeVarargs
    public ConcatFeatureMapper(FeatureMapper<RecordType>... featureMappers) {
        if (featureMappers.length == 0) {
            numFeatures = 0;
            return;
        }
        IntArraySet dimensions = new IntArraySet();

        int offset = 0;
        int i = 1;
        int numMappers = 0;
        for (FeatureMapper<RecordType> calculator : featureMappers) {
            if (calculator.numberOfFeatures() > 0) {
                numMappers += 1;
            }
        }
        this.mappers = new FeatureMapper[numMappers];
        i = 0;
        for (FeatureMapper<RecordType> calculator : featureMappers) {
            if (calculator.numberOfFeatures() > 0) {
                mappers[i++] = calculator;
                requiresMask |= calculator.hasMask();
            }
        }
        offsets = new int[numMappers + 1];
        offsets[0] = 0;
        i = 1;
        for (FeatureMapper<RecordType> calculator : mappers) {

            numFeatures += calculator.numberOfFeatures();
            offsets[i] = numFeatures;
            final MappedDimensions dims = calculator.dimensions();
            dimensions.add(dims.numDimensions());
            i++;
        }
        if (numMappers >= 1) {
            assert featureMappers.length == 0 || dimensions.size() == 1 : "All feature mappers must have the same dimensions to be concatenated.";
        }
    }

    @Override
    public int numberOfFeatures() {

        return numFeatures;
    }

    @Override
    public MappedDimensions dimensions() {
        return new MappedDimensions(numberOfFeatures());
    }

    @Override
    public void prepareToNormalize(RecordType record, int indexOfRecord) {
        for (FeatureMapper<RecordType> calculator : mappers) {
            calculator.prepareToNormalize(record, indexOfRecord);
        }
        normalizedCalled = true;
    }


    @Override
    public void mapFeatures(RecordType record, INDArray inputs, int indexOfRecord) {
        assert normalizedCalled : "prepareToNormalize must be called before mapFeatures.";
        int offset = 0;
        final int[] indicesOuter = {0, 0};
        for (FeatureMapper<RecordType> delegate : mappers) {

            final int delNumFeatures = delegate.numberOfFeatures();
            for (int j = 0; j < delNumFeatures; j++) {
                indicesOuter[0] = indexOfRecord;
                indicesOuter[1] = j + offset;
                inputs.putScalar(indicesOuter, delegate.produceFeature(record, j));
            }
            offset += delNumFeatures;
        }
    }

    @Override
    public boolean hasMask() {

        return requiresMask;
    }

    @Override
    public void maskFeatures(RecordType record, INDArray mask, int indexOfRecord) {
        if (hasMask()) {
            int offset = 0;
            final int[] indicesOuter = {0, 0};
            for (FeatureMapper<RecordType> delegate : mappers) {

                final int delNumFeatures = delegate.numberOfFeatures();
                for (int j = 0; j < delNumFeatures; j++) {
                    indicesOuter[0] = indexOfRecord;
                    indicesOuter[1] = j + offset;
                    mask.putScalar(indicesOuter, delegate.isMasked(record, j) ? 1 : 0);
                }
                offset += delNumFeatures;
            }
        }
    }

    @Override
    public boolean isMasked(RecordType record, int featureIndex) {
        if (!requiresMask) {return false;}
        int indexOfDelegate = Arrays.binarySearch(offsets, featureIndex);
        if (indexOfDelegate < 0) {
            indexOfDelegate = -(indexOfDelegate + 1) - 1;
        }
        return this.mappers[indexOfDelegate].isMasked(record, featureIndex - offsets[indexOfDelegate]);
    }

    @Override
    public float produceFeature(RecordType record, int featureIndex) {
        int indexOfDelegate = Arrays.binarySearch(offsets, featureIndex);
        if (indexOfDelegate < 0) {
            indexOfDelegate = -(indexOfDelegate + 1) - 1;
        }
        return this.mappers[indexOfDelegate].produceFeature(record, featureIndex - offsets[indexOfDelegate]);
    }


}
