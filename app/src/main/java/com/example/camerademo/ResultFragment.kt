package com.example.camerademo

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.camerademo.databinding.ResultFragmentLayoutBinding

class ResultFragment : Fragment() {
    companion object {
        fun newInstance(file: String) = ResultFragment().apply {
            val args = Bundle().apply { putString("RESULT_IMAGE_FILE", file) }
            arguments = args
        }
    }
    private lateinit var binding: ResultFragmentLayoutBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = ResultFragmentLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setResult()
    }

    private fun setResult() {
        arguments?.getString("RESULT_IMAGE_FILE")?.let {
            val uri = Uri.parse(it)
            binding.imageView.setImageURI(uri)
        }
    }
}