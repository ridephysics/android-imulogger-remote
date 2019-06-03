package com.ridephysics.imulogger_remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem

import kotlinx.android.synthetic.main.activity_main.*
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*

class MainActivity : AppCompatActivity() {
    val serverUri = "tcp://localhost:1883"
    val clientId = "imulogger-android-remote"
    val subscriptionTopic = "/imulogger/status"
    val publishTopic = "/imulogger/ctrl"
    val publishMessage = "Hello World!"

    private var mClient:MqttAndroidClient? = null
    private var mServiceName: String? = null
    private var mNdsManager: NsdManager? = null

    private val registrationListener = object : NsdManager.RegistrationListener {

        override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
            mServiceName = NsdServiceInfo.serviceName
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e("IMULOGGER", "mDNS registration failed: $errorCode")
        }

        override fun onServiceUnregistered(arg0: NsdServiceInfo) {
            Log.e("IMULOGGER", "mDNS service unregistered")
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e("IMULOGGER", "mDNS unregistration failed: $errorCode")
        }
    }

    fun registerService() {
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

        fab.setOnClickListener { view ->
            publishMessage()
        }

        mClient = MqttAndroidClient(applicationContext, serverUri, clientId)
        mClient!!.setCallback(object: MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                if (reconnect) {
                    Log.i("IMULOGGER", "Reconnected to $serverURI")
                    subscribeToTopic()
                }
                else {
                    Log.i("IMULOGGER", "Connected to $serverURI")
                }
            }

            override fun connectionLost(cause: Throwable?) {
                Log.i("IMULOGGER", "The connection was lost")
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                Log.i("IMULOGGER", "Incoming message: ${String(message!!.payload)}")
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
                    mClient!!.setBufferOpts(disconnectedBufferOptions);
                    subscribeToTopic()
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.i("IMULOGGER", "Failed to connect to: $serverUri")
                }

            })
        }
        catch (e:MqttException) {
            e.printStackTrace()
        }
    }

    fun subscribeToTopic() {
        try {
            mClient!!.subscribe(subscriptionTopic, 0, null, object: IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i("IMULOGGER", "subscribed")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.i("IMULOGGER", "failed to subscribe")
                }

            })

            mClient!!.subscribe(subscriptionTopic, 0, object: IMqttMessageListener {
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    Log.i("IMULOGGER", "Message: $topic : ${String(message!!.payload)}")
                }

            })
        }
        catch (e:MqttException) {
            e.printStackTrace()
        }
    }

    fun publishMessage() {
        try {
            val msg = MqttMessage().apply {
                payload = publishMessage.toByteArray()
            }
            mClient!!.publish(publishTopic, msg)
            Log.i("IMULOGGER", "message published")

            if (!mClient!!.isConnected()) {
                Log.i("IMULOGGER", "${mClient!!.bufferedMessageCount} messages in buffer")
            }
        }
        catch (e:MqttException) {
            e.printStackTrace()
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
}
