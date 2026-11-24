package com.example.sensorar;

// 烘焙进度的轻量回调。done/total 均为正整数，total<=0 表示总量未知。
// 由后台烘焙线程调用，UI 侧需切回主线程更新。
interface Progress {
    void onProgress(int done, int total, String stage);
}
