package com.github.libretube.ui.models

import android.content.Context
import android.os.Parcelable
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.api.TrendingCategory
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.extensions.TAG
import com.github.libretube.extensions.toastFromMainDispatcher
import com.github.libretube.helpers.PreferenceHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrendsViewModel : ViewModel() {
    data class TrendingStreams(val region: String, val streams: List<StreamItem>)

    val trendingVideos = MutableLiveData<Map<TrendingCategory, TrendingStreams>>()
    var recyclerViewState: Parcelable? = null

    private val currentJobs = mutableMapOf<TrendingCategory, Job>()

    fun fetchTrending(context: Context, category: TrendingCategory) {
        // A ViewPager can create adjacent pages in parallel. Cancel only the request
        // belonging to the same category, never the visible neighbour.
        currentJobs[category]?.cancel()

        currentJobs[category] = viewModelScope.launch {
            try {
                val region = PreferenceHelper.getTrendingRegion(context)
                val response = withContext(Dispatchers.IO) {
                    MediaServiceRepository.instance.getTrending(region, category)
                }
                setStreamsForCategory(category, TrendingStreams(region, response))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG(), e.stackTraceToString())
                context.toastFromMainDispatcher(e.localizedMessage.orEmpty())
            }
        }
    }

    fun setStreamsForCategory(category: TrendingCategory, streams: TrendingStreams) {
        val newState = trendingVideos.value.orEmpty()
            .toMutableMap()
            .apply {
                put(category, streams)
            }
        trendingVideos.postValue(newState)
    }
}
