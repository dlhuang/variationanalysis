package org.campagnelab.dl.model.utils;

import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.campagnelab.dl.varanalysis.protobuf.BaseInformationRecords;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.PrintWriter;
import java.util.List;

/**
 * Created by rct66 on 6/23/16.
 */
public class ProtoPredictor {

    public static final int POSITIVE_STRAND = 0;
    public static final int NEGATIVE_STRAND = 1;
    private MultiLayerNetwork model;
    private FeatureMapper mapper;

    public ProtoPredictor(MultiLayerNetwork model, FeatureMapper mapper){
        this.model = model;
        this.mapper = mapper;
    }

    public static List<BaseInformationRecords.NumberWithFrequency> compressFreq(List<Integer> numList) {
        //compress into map
        Int2IntArrayMap freqMap = new Int2IntArrayMap(100);
        for (int num : numList) {
            Integer freq = freqMap.putIfAbsent(num, 1);
            if (freq != null) {
                freqMap.put(num, freq + 1);
            }
        }
        //iterate map into freqlist
        List<BaseInformationRecords.NumberWithFrequency> freqList = new ObjectArrayList<>(freqMap.size());
        for (Int2IntMap.Entry entry : freqMap.int2IntEntrySet()) {
            BaseInformationRecords.NumberWithFrequency.Builder freqBuilder = BaseInformationRecords.NumberWithFrequency.newBuilder();
            freqBuilder.setFrequency(entry.getIntValue());
            freqBuilder.setNumber(entry.getIntKey());
            freqList.add(freqBuilder.build());
        }
        return freqList;
    }

    private boolean mutPrediction(BaseInformationRecords.BaseInformation record) {
        INDArray testFeatures = Nd4j.zeros(1, mapper.numberOfFeatures());
        mapper.mapFeatures(record, testFeatures, 0);
        INDArray testPredicted = model.output(testFeatures, false);
        float[] probabilities = testPredicted.getRow(0).data().asFloat();
        boolean predictedMutated = probabilities[0] > probabilities[1];
        return predictedMutated;
    }
}