package com.ridephysics.imulogger_remote

import android.content.Context
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.ridephysics.imulogger_remote.databinding.ActivityMainBinding
import info.mqtt.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList


@ExperimentalUnsignedTypes
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val serverUri = "tcp://localhost:1883"
    private val clientId = "imulogger-android-remote"
    private val subscriptionTopic = "/imulogger/status"
    private val publishTopic = "/imulogger/ctrl"

    private var mClient: MqttAndroidClient? = null
    private var mServiceName: String? = null
    private var mNdsManager: NsdManager? = null
    private var mHistoryAdapter: HistoryAdapter? = null
    private var mTvStatus: TextView? = null
    private var mTvFilename: TextView? = null
    private var mTvsamplerate: TextView? = null
    private var mTvSdcardStatus: TextView? = null
    private var mTvBroadcast: TextView? = null

    private var mSdcardRefed: Boolean = false
    private var mBroadcastEnabled: Boolean = false

    enum class IMUCmd(val cmd: UByte) {
        FULLREPORT(0x00u),
        FILENAME(0x01u),
        ENABLED(0x02u),
        IMUSTATUS(0x03u),
        SAMPLERATE(0x04u),
        SDCARD(0x05u),
        BROADCAST(0x06u),
    }

    enum class IMUStatus(val v: UByte) {
        STANDBY(0x01u),
        SLOW(0x02u),
        STILLNESS(0x04u),
        STABLE(0x08u),
        TRANSIENT(0x10u),
        UNRELIABLE(0x20u),
        R1(0x40u),
        R2(0x80u),
    }

    private val registrationListener = object : NsdManager.RegistrationListener {

        override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
            mServiceName = NsdServiceInfo.serviceName
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            loge("mDNS registration failed: $errorCode")
        }

        override fun onServiceUnregistered(arg0: NsdServiceInfo) {
            loge("mDNS service unregistered")
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            loge("mDNS unregistration failed: $errorCode")
        }
    }

    private fun registerService() {
        // we're running a mosquitto mqtt broker inside Termux
        // let this app advertize it to the network
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "imulogger-mqtt-broker"
            serviceType = "_imulogger_mqtt_broker._tcp"
            port = 1883
        }

        mNdsManager = (getSystemService(Context.NSD_SERVICE) as NsdManager).apply {
            registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }
    }

    private fun setupStatusInfo(id: Int, label: String): TextView {
        val vg = findViewById<ViewGroup>(id)
        val tv_label = vg.findViewById<TextView>(R.id.label)
        val tv_text = vg.findViewById<TextView>(R.id.text)

        tv_label.text = label + ": "
        tv_text.text = "unknown"

        return tv_text
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        setSupportActionBar(binding.toolbar)
        registerService()

        mTvStatus = setupStatusInfo(R.id.status, "Status")
        mTvFilename = setupStatusInfo(R.id.filename, "Filename")
        mTvsamplerate = setupStatusInfo(R.id.samplerate, "Samplerate")
        mTvSdcardStatus = setupStatusInfo(R.id.sdcardstatus, "SDCARD")
        mTvBroadcast = setupStatusInfo(R.id.broadcast, "Broadcast")

        mHistoryAdapter = HistoryAdapter()
        binding.contentMain.history.layoutManager = LinearLayoutManager(this)
        binding.contentMain.history.adapter = mHistoryAdapter

        mClient = MqttAndroidClient(applicationContext, serverUri, clientId)
        mClient!!.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                if (reconnect) {
                    log("Reconnected to $serverURI")
                    onConnected()
                } else {
                    log("Connected to $serverURI")
                }
            }

            override fun connectionLost(cause: Throwable?) {
                loge("The connection was lost")
                if (cause != null)
                    loge(Log.getStackTraceString(cause))
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                log("Incoming message: ${String(message!!.payload)}")
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {

            }
        })

        val connectOptions = MqttConnectOptions().apply {
            setAutomaticReconnect(true)
            setCleanSession(true)
        }

        try {
            mClient!!.connect(connectOptions, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    val disconnectedBufferOptions = DisconnectedBufferOptions().apply {
                        setBufferEnabled(true)
                        setBufferSize(100)
                        setPersistBuffer(false)
                        setDeleteOldestMessages(false)
                    }
                    mClient!!.setBufferOpts(disconnectedBufferOptions)
                    onConnected()
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    loge("Failed to connect to: $serverUri")
                    if (exception != null)
                        loge(Log.getStackTraceString(exception))

                }

            })
        } catch (e: MqttException) {
            e.printStackTrace()
            loge(Log.getStackTraceString(e))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mClient?.disconnect()
        mClient?.unregisterResources()
    }

    fun onConnected() {
        mqttsend_fullreport()

        try {
            mClient!!.subscribe(subscriptionTopic, 0, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    log("subscribed")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    loge("failed to subscribe")
                    if (exception != null)
                        loge(Log.getStackTraceString(exception))
                }

            })

            mClient!!.subscribe(subscriptionTopic, 0, object : IMqttMessageListener {
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (message == null) {
                        loge("received null message")
                        return
                    }

                    if (message.payload == null) {
                        loge("received null payload")
                        return
                    }

                    runOnUiThread {
                        when (message.payload[0].toUByte()) {
                            IMUCmd.ENABLED.cmd -> {
                                val enabled = message.payload[1].toUByte()

                                log("enable-status: $enabled")

                                binding.fab.show()
                                if (enabled == 0x00u.toUByte()) {
                                    binding.fab.setImageResource(android.R.drawable.ic_media_play)
                                    binding.contentMain.statuscolor.setBackgroundColor(
                                        Color.parseColor(
                                            "#ff0000"
                                        )
                                    )

                                    binding.fab.setOnClickListener { _ ->
                                        mqttsend_enabled(true)
                                    }
                                } else {
                                    binding.fab.setImageResource(R.drawable.ic_stop_black_24dp)
                                    binding.contentMain.statuscolor.setBackgroundColor(
                                        Color.parseColor(
                                            "#00ff00"
                                        )
                                    )

                                    binding.fab.setOnClickListener { _ ->
                                        mqttsend_enabled(false)
                                    }
                                }
                            }

                            IMUCmd.IMUSTATUS.cmd -> {
                                val status = message.payload[1].toUByte()
                                val statuslist = ArrayList<String>()

                                if ((status and IMUStatus.STANDBY.v) == IMUStatus.STANDBY.v)
                                    statuslist.add("standby")
                                if ((status and IMUStatus.SLOW.v) == IMUStatus.SLOW.v)
                                    statuslist.add("slow")
                                if ((status and IMUStatus.STILLNESS.v) == IMUStatus.STILLNESS.v)
                                    statuslist.add("stillness")
                                if ((status and IMUStatus.STABLE.v) == IMUStatus.STABLE.v)
                                    statuslist.add("stable")
                                if ((status and IMUStatus.TRANSIENT.v) == IMUStatus.TRANSIENT.v)
                                    statuslist.add("transient")
                                if ((status and IMUStatus.UNRELIABLE.v) == IMUStatus.UNRELIABLE.v)
                                    statuslist.add("unreliable")
                                if ((status and IMUStatus.R1.v) == IMUStatus.R1.v)
                                    statuslist.add("r1")
                                if ((status and IMUStatus.R2.v) == IMUStatus.R2.v)
                                    statuslist.add("r2")

                                val statusString = statuslist.joinToString()
                                log("new status: $statusString")
                                mTvStatus?.text = statusString
                            }
                            IMUCmd.FILENAME.cmd -> {
                                val filename = String(message.payload.drop(1).toByteArray())
                                log("new filename: $filename")
                                mTvFilename?.text = filename
                            }
                            IMUCmd.SAMPLERATE.cmd -> {
                                val samplerate =
                                    ByteBuffer.wrap(message.payload.drop(1).toByteArray())
                                        .order(ByteOrder.LITTLE_ENDIAN).int
                                log("new samplerate: $samplerate")

                                val sdf = SimpleDateFormat("dd/M/yyyy hh:mm:ss")
                                val currentDate = sdf.format(Date())

                                mTvsamplerate?.text = "$samplerate ($currentDate)"
                            }
                            IMUCmd.SDCARD.cmd -> {
                                val refed = message.payload[1].toUByte()
                                log("sdcard: refed=$refed")
                                mTvSdcardStatus?.text =
                                    if (refed != 0x00u.toUByte()) "refed" else "not refed"
                                mSdcardRefed = if (refed != 0x00u.toUByte()) true else false
                            }
                            IMUCmd.BROADCAST.cmd -> {
                                val broadcast = message.payload[1].toUByte()
                                log("broadcast: $broadcast")
                                mTvBroadcast?.text =
                                    if (broadcast != 0x00u.toUByte()) "enabled" else "disabled"
                                mBroadcastEnabled =
                                    if (broadcast != 0x00u.toUByte()) true else false
                            }
                            else -> loge("unsupported cmd ${message.payload[0]}")
                        }
                    }
                }

            })
        } catch (e: MqttException) {
            e.printStackTrace()
            loge(Log.getStackTraceString(e))
        }
    }

    private fun mqttsend_fullreport() {
        mqttsend_raw(byteArrayOf(IMUCmd.FULLREPORT.cmd.toByte()))
    }

    private fun mqttsend_filename(name: String) {
        mqttsend_raw(byteArrayOf(IMUCmd.FILENAME.cmd.toByte()) + name.toByteArray())
    }

    private fun mqttsend_enabled(enabled: Boolean) {
        mqttsend_raw(byteArrayOf(IMUCmd.ENABLED.cmd.toByte(), if (enabled) 0x01 else 0x00))
    }

    private fun mqttsend_sdcard_refed(refed: Boolean) {
        mqttsend_raw(byteArrayOf(IMUCmd.SDCARD.cmd.toByte(), if (refed) 0x01 else 0x00))
    }

    private fun mqttsend_broadcast(broadcast: Boolean) {
        mqttsend_raw(byteArrayOf(IMUCmd.BROADCAST.cmd.toByte(), if (broadcast) 0x01 else 0x00))
    }


    private fun mqttsend_raw(_payload: ByteArray) {
        var debugstr = ""
        for (b in _payload) {
            debugstr += String.format("%02X", b)
        }
        log("send: $debugstr")

        try {
            val msg = MqttMessage().apply {
                payload = _payload
            }
            mClient!!.publish(publishTopic, msg)
            log("message published")

            if (!mClient!!.isConnected) {
                log("${mClient!!.bufferedMessageCount} messages in buffer")
            }
        } catch (e: MqttException) {
            e.printStackTrace()
            loge(Log.getStackTraceString(e))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_filename -> {
                val alertDialog = AlertDialog.Builder(this@MainActivity).create()

                val input = EditText(this@MainActivity)
                input.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                input.setText("/sdcard/");

                alertDialog.apply {
                    setTitle("Filename")
                    setMessage("Enter the new filename")
                    setView(input)
                    setButton(AlertDialog.BUTTON_NEGATIVE, "CANCEL") { dialog, _ ->
                        run {
                            dialog.dismiss()
                        }
                    }
                    setButton(AlertDialog.BUTTON_POSITIVE, "OK") { dialog, _ ->
                        run {
                            mqttsend_filename(input.text.toString())
                            dialog.dismiss()
                        }
                    }
                    show()
                }

                true
            }
            R.id.action_refresh -> {
                mqttsend_fullreport()
                true
            }
            R.id.action_toggle_console -> {
                binding.contentMain.history.visibility =
                    when (binding.contentMain.history.visibility) {
                        View.GONE -> View.VISIBLE
                        View.INVISIBLE -> View.VISIBLE
                        View.VISIBLE -> View.GONE
                        else -> View.GONE
                    }
                true
            }
            R.id.action_toggle_sdcard -> {
                mqttsend_sdcard_refed(!mSdcardRefed)
                true
            }
            R.id.action_toggle_broadcast -> {
                mqttsend_broadcast(!mBroadcastEnabled)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun aloglevel(level: Int, text: String) {
        val tag = "IMULOGGER"
        when (level) {
            Log.ERROR -> Log.e(tag, text)
            Log.WARN -> Log.w(tag, text)
            else -> Log.i(tag, text)
        }
    }

    private fun log(text: String, level: Int = Log.INFO) {
        aloglevel(level, text)

        val color = when (level) {
            Log.ERROR -> Color.parseColor("#F44336")
            Log.WARN -> Color.parseColor("#FFEB3B")
            else -> Color.WHITE
        }


        runOnUiThread {
            mHistoryAdapter!!.add(text, color)

            if (!binding.contentMain.history.canScrollVertically(0))
                binding.contentMain.history.scrollToPosition(mHistoryAdapter!!.itemCount - 1)
        }
    }

    private fun loge(text: String) {
        log(text, Log.ERROR)
    }

    private fun logw(text: String) {
        log(text, Log.WARN)
    }
}
