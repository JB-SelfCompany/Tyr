package com.jbselfcompany.tyr.ui

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.jbselfcompany.tyr.utils.LocaleHelper

/**
 * Base activity that applies language and theme preferences.
 * All activities should extend this class to support language switching.
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Apply theme preference
        LocaleHelper.applyTheme(this)
    }
}
