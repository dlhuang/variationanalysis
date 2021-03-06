package org.campagnelab.dl.genotype.mappers;

import org.campagnelab.dl.framework.mappers.ConfigurableFeatureMapper;
import org.campagnelab.dl.genotype.helpers.GenotypeHelper;
import org.campagnelab.dl.varanalysis.protobuf.BaseInformationRecords;

import java.util.Properties;

/**
 * Label that encodes how many distinct alleles are present in a sample. Assume two alleles are present and ploidy is 2.
 * <p>
 * This mapper will produce [ 0 1 ]. With one allele and ploidy=2: [ 1 0 ].
 * With ploidy=3 and three alleles: [0 0 1]
 * Created by fac2003 on 12/20/16.
 */
public class NumDistinctAllelesLabelMapper extends CountSortingLabelMapper implements ConfigurableFeatureMapper {


    private final float epsilon;
    public int ploidy;

    /**
     *
     * @param sortCounts
     * @param ploidy
     * @param epsilon amount of label smoothing to apply.
     */
    public NumDistinctAllelesLabelMapper(boolean sortCounts, int ploidy, float epsilon) {
        super(sortCounts);
        this.ploidy = ploidy;
        this.epsilon = epsilon;
    }

    public NumDistinctAllelesLabelMapper(boolean sortCounts, int ploidy) {

        this(sortCounts, ploidy, 0);
    }

    @Override
    public int numberOfLabels() {
        return ploidy+1;
    }

    @Override
    public float produceLabel(BaseInformationRecords.BaseInformation record, int labelIndex) {
        final String trueGenotype = sortedCountRecord.getTrueGenotype();
        return label(labelIndex, trueGenotype);
    }

    protected float label(int labelIndex, String trueGenotype) {
        int numDistinctAlleles = GenotypeHelper.getAlleles(trueGenotype).size();
        float v = epsilon / (numberOfLabels() - 1f);
        return (labelIndex == numDistinctAlleles ) ? 1f-epsilon : v;

    }

    public static final java.lang.String PLOIDY_PROPERTY = "genotypes.ploidy";

    @Override
    public void configure(Properties readerProperties) {

        String value = readerProperties.getProperty(PLOIDY_PROPERTY);
        try {
            ploidy = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new RuntimeException("Unable to read ploidy from sbi properties file.");
        }
    }
}
