package org.campagnelab.dl.gpus;
import org.nd4j.jita.conf.CudaEnvironment;
/**
 * Created by fac2003 on 12/1/16.
 */
public class InitializeCudaEnvironmentOnGPU {
    public InitializeCudaEnvironmentOnGPU() {
        final String execution_platform = System.getenv("EXECUTION_PLATFORM");
        if ("cuda".equals(execution_platform)) {
            final long GB = 1024 * 1024 * 1024L;

            CudaEnvironment.getInstance().getConfiguration()
                    .enableDebug(false)
                    .allowMultiGPU(true)
                    .setMaximumGridSize(512)
                    .setMaximumBlockSize(512)
                    .setMaximumDeviceCacheableLength(1 * GB)
                    .setMaximumDeviceCache(8L * GB)
                    .setMaximumHostCacheableLength(1 * GB)
                    .setMaximumHostCache(16L * GB)
                    // cross - device access is used for faster model averaging over pcie
                    .allowCrossDeviceAccess(true);
            System.out.println("Configured CUDA environment.");
        }

    }
}
