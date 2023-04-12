package com.example.camerademo

import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.fragment.app.commit
import com.example.camerademo.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        if (savedInstanceState == null) {
            supportFragmentManager.commit { replace(R.id.fragment_container, Camera2Fragment()) }
        }
    }

    fun showResult(uri: Uri) {
        val file = uri.toString()
        supportFragmentManager.commit {
            replace(R.id.fragment_container, ResultFragment.newInstance(file))
            addToBackStack(null)
        }
    }
}