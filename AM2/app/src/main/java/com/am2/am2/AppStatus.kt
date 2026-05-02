package com.am2.am2

import androidx.lifecycle.MutableLiveData

object AppStatus {
    val isForeground = MutableLiveData<Boolean>(false)
    
    private var activityCount = 0

    fun onActivityStarted() {
        activityCount++
        if (activityCount == 1) {
            isForeground.postValue(true)
        }
    }

    fun onActivityStopped() {
        activityCount--
        if (activityCount == 0) {
            isForeground.postValue(false)
        }
    }
}
