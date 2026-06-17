package com.example

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

fun loadAndShowStreamingAd(context: Context, activity: Activity, onReward: () -> Unit) {
    val adRequest = AdRequest.Builder().build()
    
    // Google's Standard Test ID for Rewarded Ads
    RewardedAd.load(context, "ca-app-pub-3940256099942544/5224354917", adRequest, object : RewardedAdLoadCallback() {
        override fun onAdLoaded(rewardedAd: RewardedAd) {
            rewardedAd.fullScreenContentCallback = object: FullScreenContentCallback() {}
            rewardedAd.show(activity) { _ -> onReward() }
        }
        override fun onAdFailedToLoad(error: com.google.android.gms.ads.LoadAdError) {
            onReward() // Fail-safe: Give them the reward if they have no internet
        }
    })
}
