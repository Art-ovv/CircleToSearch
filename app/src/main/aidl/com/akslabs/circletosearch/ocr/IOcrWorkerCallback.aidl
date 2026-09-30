package com.akslabs.circletosearch.ocr;
import android.os.SharedMemory;
oneway interface IOcrWorkerCallback {
    void complete(long requestId, int status, in SharedMemory result);
}
