package com.papaji.alpharunner

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import com.papaji.alpharunner.ui.GameView

class MainActivity : ComponentActivity() {

    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ─── FIX: force landscape — the game world is drawn in landscape
        //          coordinates. Without this the canvas is portrait and
        //          everything renders rotated 90°.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Keep screen alive during gameplay
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        gameView = GameView(this)
        setContentView(gameView)
        enableFullscreen()
    }

    private fun enableFullscreen() {
        val decorView = window.decorView
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            decorView.windowInsetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onPause()   { super.onPause();  gameView.pause() }
    override fun onResume()  { super.onResume(); enableFullscreen(); gameView.resume() }
    override fun onDestroy() { super.onDestroy(); gameView.releaseResources() }
}