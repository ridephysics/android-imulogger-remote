package com.ridephysics.imulogger_remote

import android.content.Context
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.util.Log
import android.view.Menu
import android.view.MenuItem

import kotlinx.android.synthetic.main.activity_main.*
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*

class MainActivity : AppCompatActivity() {
    private val serverUri = "tcp://localhost:1883"
    private val clientId = "imulogger-android-remote"
    private val subscriptionTopic = "/imulogger/status"
    private val publishTopic = "/imulogger/ctrl"
    private val publishMessage = "Hello World!"

    private var mClient:MqttAndroidClient? = null
    private var mServiceName: String? = null
    private var mNdsManager: NsdManager? = null
    private var mHistoryAdapter: HistoryAdapter? = null

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
            setPort(1883)
        }

        mNdsManager = (getSystemService(Context.NSD_SERVICE) as NsdManager).apply {
            registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)
        registerService()

        val recyclerView = findViewById<RecyclerView>(R.id.history)
        recyclerView.layoutManager = LinearLayoutManager(this)

        mHistoryAdapter = HistoryAdapter()
        recyclerView.adapter = mHistoryAdapter

        fab.setOnClickListener { view ->
            publishMessage()
        }

        mClient = MqttAndroidClient(applicationContext, serverUri, clientId)
        mClient!!.setCallback(object: MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                if (reconnect) {
                    log("Reconnected to $serverURI")
                    subscribeToTopic()
                }
                else {
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
                    subscribeToTopic()
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    loge("Failed to connect to: $serverUri")
                    if (exception != null)
                        loge(Log.getStackTraceString(exception))

                }

            })
        }
        catch (e:MqttException) {
            e.printStackTrace()
            loge(Log.getStackTraceString(e))
        }
    }

    fun subscribeToTopic() {
        try {
            mClient!!.subscribe(subscriptionTopic, 0, null, object: IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    log("subscribed")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    loge("failed to subscribe")
                    if (exception != null)
                        loge(Log.getStackTraceString(exception))
                }

            })

            mClient!!.subscribe(subscriptionTopic, 0, object: IMqttMessageListener {
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    log("Message: $topic : ${String(message!!.payload)}")
                }

            })
        }
        catch (e:MqttException) {
            e.printStackTrace()
            loge(Log.getStackTraceString(e))
        }
    }

    private fun publishMessage() {
        try {
            val msg = MqttMessage().apply {
                payload = publishMessage.toByteArray()
            }
            mClient!!.publish(publishTopic, msg)
            log("message published")

            if (!mClient!!.isConnected) {
                log("${mClient!!.bufferedMessageCount} messages in buffer")
            }
        }
        catch (e:MqttException) {
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
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun aloglevel(level: Int, text: String) {
        val tag = "IMULOGGER"
        when(level) {
            Log.ERROR -> Log.e(tag, text)
            Log.WARN -> Log.w(tag, text)
            else -> Log.i(tag, text)
        }
    }

    private fun log(text: String, level:Int = Log.INFO) {
        aloglevel(level, text)

        val color = when(level) {
            Log.ERROR -> Color.parseColor("#F44336")
            Log.WARN -> Color.parseColor("#FFEB3B")
            else -> Color.WHITE
        }

        runOnUiThread({ mHistoryAdapter?.add(text, color) })
    }

    private fun loge(text: String) {
        log(text, Log.ERROR)
    }

    private fun logw(text: String) {
        log(text, Log.WARN)
    }
}
