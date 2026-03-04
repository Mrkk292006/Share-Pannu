package com.localshare.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.localshare.app.databinding.ActivityPhoneToPhoneBinding

class ActivityPhoneToPhone : AppCompatActivity() {

    private lateinit var binding: ActivityPhoneToPhoneBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhoneToPhoneBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnSender.setOnClickListener {
            startActivity(Intent(this, ActivityP2PSender::class.java))
        }

        binding.btnReceiver.setOnClickListener {
            startActivity(Intent(this, ActivityP2PReceiver::class.java))
        }
    }
}
