package com.akslabs.circletosearch.ocr;
import android.os.SharedMemory;
import com.akslabs.circletosearch.ocr.IOcrWorkerCallback;
interface IOcrWorker {
    void submit(long requestId, String packId, in SharedMemory pixels, int width, int height, int rowBytes, IOcrWorkerCallback callback);
    void cancel(long requestId);
    void shutdown();
}
