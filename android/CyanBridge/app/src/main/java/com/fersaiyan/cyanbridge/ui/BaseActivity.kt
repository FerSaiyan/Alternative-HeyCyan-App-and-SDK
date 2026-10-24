package com.fersaiyan.cyanbridge.ui
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import androidx.appcompat.app.AppCompatActivity
import java.lang.ref.WeakReference
open class BaseActivity : AppCompatActivity() {

    /**
     * Whether the current Activity is in the foreground.
     */
    private var isActive: Boolean = false

    /**
     * The instance of the current Activity.
     */
    protected var activity: Activity? = null

    /** Weak reference to the current Activity to prevent memory leaks */
    private var activityWR: WeakReference<Activity>? = null


    /**
     * Log output tag
     */
    protected val TAG: String = this.javaClass.simpleName

    private val handler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activity = this
        activityWR = WeakReference(activity!!)
        val view = this.window.decorView
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    override fun onRestart() {
        super.onRestart()
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
        isActive = true
    }

    override fun onPause() {
        super.onPause()
        isActive = false
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        activity = null
    }



}
