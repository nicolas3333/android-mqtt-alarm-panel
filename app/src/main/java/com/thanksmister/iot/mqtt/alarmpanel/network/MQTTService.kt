/*
 * Copyright (c) 2018 ThanksMister LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.thanksmister.iot.mqtt.alarmpanel.network


import android.R.id.message
import android.content.Context
import android.text.TextUtils
import com.thanksmister.iot.mqtt.alarmpanel.R
import com.thanksmister.iot.mqtt.alarmpanel.utils.MqttUtils
import com.thanksmister.iot.mqtt.alarmpanel.utils.StringUtils
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import timber.log.Timber
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.security.spec.InvalidKeySpecException
import java.util.concurrent.atomic.AtomicBoolean

class MQTTService(private var context: Context, options: MQTTOptions,
                  private var listener: MqttManagerListener?) : MQTTServiceInterface {

    private var mqttClient: MqttAndroidClient? = null
    private var mqttOptions: MQTTOptions? = null
    private val mReady = AtomicBoolean(false)

    init {
        initialize(options)
    }

    override fun reconfigure(context: Context,
                             newOptions: MQTTOptions,
                             listener: MqttManagerListener) {
        try {
            close()
        } catch (e: MqttException) {
            // empty
        }
        this.listener = listener
        this.context = context
        initialize(newOptions)
    }

    interface MqttManagerListener {
        fun subscriptionMessage(id: String, topic: String, payload: String)
        fun handleMqttException(errorMessage: String)
        fun handleMqttDisconnected()
        fun handleMqttConnected()
    }

    override fun isReady(): Boolean {
        return mReady.get()
    }

    @Throws(MqttException::class)
    override fun close() {
        Timber.d("close")
        mqttClient?.let {
            it.setCallback(null)
            if (it.isConnected) {
                it.disconnect(0)
            }
            mqttClient = null
            listener = null
            mqttOptions = null
        }
        mReady.set(false)
    }

    override fun publishAlarm(payload: String) {
        try {
            if (isReady) {
                mqttClient?.let {
                    if (!it.isConnected) {
                        // if for some reason the mqtt client has disconnected, we should try to connect
                        // it again.
                        try {
                            initializeMqttClient()
                        } catch (e: MqttException) {
                            if (listener != null) {
                                listener!!.handleMqttException("Could not initialize MQTT: " + e.message)
                            }
                        } catch (e: IOException) {
                            if (listener != null) {
                                listener!!.handleMqttException("Could not initialize MQTT: " + e.message)
                            }
                        } catch (e: GeneralSecurityException) {
                            if (listener != null) {
                                listener!!.handleMqttException("Could not initialize MQTT: " + e.message)
                            }
                        }
                    }
                }
                val mqttMessage = MqttMessage()
                mqttMessage.payload = payload.toByteArray()
                mqttOptions?.let {
                    mqttMessage.isRetained = it.getRetain()
                    sendMessage(it.getAlarmCommandTopic(), mqttMessage)
                }
            }
        } catch (e: MqttException) {
            if (listener != null) {
                listener!!.handleMqttException("Exception while subscribing: " + e.message)
            }
        }
    }

    override fun publishState(command: String, payload: String) {
        try {
            if (isReady) {
                mqttClient?.let {
                    if ( !it.isConnected) {
                        // if for some reason the mqtt client has disconnected, we should try to connect
                        // it again.
                        try {
                            initializeMqttClient()
                        } catch (e: MqttException) {
                            if (listener != null) {
                                listener!!.handleMqttException("Could not initialize MQTT: " + e.message)
                            }
                        } catch (e: IOException) {
                            if (listener != null) {
                                listener!!.handleMqttException("Could not initialize MQTT: " + e.message)
                            }
                        } catch (e: GeneralSecurityException) {
                            if (listener != null) {
                                listener!!.handleMqttException("Could not initialize MQTT: " + e.message)
                            }
                        }
                    }
                }

                // TODO append the "command" part
                val mqttMessage = MqttMessage()
                mqttMessage.payload = payload.toByteArray()
                mqttOptions?.let {
                    Timber.d("Publishing: $payload")
                    Timber.d("Base Topic: ${it.getBaseTopic()}")
                    Timber.d("Command Topic: $command")
                    mqttMessage.isRetained = it.getRetain()
                    sendMessage( it.getBaseTopic() + "/" + command, mqttMessage)
                }
            }
        } catch (e: MqttException) {
            if (listener != null) {
                listener!!.handleMqttException("Exception while subscribing: " + e.message)
            }
        }
    }

    /**
     * Initialize a Cloud IoT Endpoint given a set of configuration options.
     * @param options Cloud IoT configuration options.
     */
    private fun initialize(options: MQTTOptions) {
        Timber.d("initialize")
        try {
            mqttOptions = options
            Timber.i("Service Configuration:")
            Timber.i("Client ID: " + mqttOptions!!.getClientId())
            Timber.i("Username: " + mqttOptions!!.getUsername())
            Timber.i("Password: " + mqttOptions!!.getPassword())
            Timber.i("TslConnect: " + mqttOptions!!.getTlsConnection())
            Timber.i("MQTT Configuration:")
            Timber.i("Broker: " + mqttOptions?.brokerUrl)
            Timber.i("Subscribed to state topics: " + StringUtils.convertArrayToString(mqttOptions!!.getStateTopics()))
            Timber.i("Publishing to alarm topic: " + mqttOptions!!.getAlarmCommandTopic())
            Timber.i("Publishing to command topic: " + mqttOptions!!.getBaseTopic())
            mqttOptions?.let {
                if (it.isValid) {
                    initializeMqttClient()
                } else {
                    if (listener != null) {
                        listener!!.handleMqttDisconnected()
                    }
                }
            }
        } catch (e: MqttException) {
            if (listener != null) {
                listener!!.handleMqttException(context.getString(R.string.error_mqtt_connection))
            }
        } catch (e: IOException) {
            if (listener != null) {
                listener!!.handleMqttException(context.getString(R.string.error_mqtt_connection))
            }
        } catch (e: GeneralSecurityException) {
            if (listener != null) {
                listener!!.handleMqttException(context.getString(R.string.error_mqtt_connection))
            }
        }
    }

    @Throws(MqttException::class, IOException::class, NoSuchAlgorithmException::class, InvalidKeySpecException::class)
    private fun initializeMqttClient() {
        Timber.d("initializeMqttClient")
        try {
            val options = MqttConnectOptions()
            options.isAutomaticReconnect = true
            options.isCleanSession = false
            mqttOptions?.let {
                mqttClient = MqttAndroidClient(context, mqttOptions?.brokerUrl, mqttOptions!!.getClientId())
                if (!TextUtils.isEmpty(it.getUsername()) && !TextUtils.isEmpty(it.getPassword())) {
                    options.userName = it.getUsername()
                    options.password = it.getPassword().toCharArray()
                }
            }

            try {
                mqttClient?.connect(options, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken) {
                        val disconnectedBufferOptions = DisconnectedBufferOptions()
                        disconnectedBufferOptions.isBufferEnabled = true
                        disconnectedBufferOptions.bufferSize = 100
                        disconnectedBufferOptions.isPersistBuffer = false
                        disconnectedBufferOptions.isDeleteOldestMessages = false
                        if (mqttClient != null) {
                            try {
                                mqttClient!!.setBufferOpts(disconnectedBufferOptions)
                            } catch (e: NullPointerException) {
                                Timber.e(e.message)
                            }
                        }
                        if (mqttOptions != null) {
                            subscribeToTopics(mqttOptions!!.getStateTopics())
                        }
                        listener?.handleMqttConnected()
                    }
                    override fun onFailure(asyncActionToken: IMqttToken, exception: Throwable) {
                        if (listener != null && mqttOptions != null) {
                            Timber.e("Failed to connect to: " + mqttOptions?.brokerUrl + " exception: " + exception)
                            //listener!!.handleMqttException("Error connecting to the broker and port: " + mqttOptions!!.brokerUrl)
                            listener!!.handleMqttException(context.getString(R.string.error_mqtt_subscription))
                        }
                    }
                })
            } catch (e: NullPointerException) {
                Timber.e(e, e.message)
                e.printStackTrace()
            } catch (e: MqttException) {
                Timber.e(e, "MqttException")
                if (listener != null) {
                    listener?.handleMqttException("" + e.message)
                }
            }
            mReady.set(true)
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        } catch (e: NullPointerException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
            if (listener != null) {
                listener!!.handleMqttException("" + e.message)
            }
        }
    }

    @Throws(MqttException::class)
    private fun sendMessage(mqttTopic: String?, mqttMessage: MqttMessage) {
        Timber.d("sendMessage")
        mqttClient?.let {
            if (isReady  && it.isConnected) {
                try {
                    it.publish(mqttTopic, mqttMessage)
                    Timber.d("Command Topic: $mqttTopic Payload: $message")
                } catch (e: NullPointerException) {
                    Timber.e(e.message)
                } catch (e: MqttException) {
                    Timber.e("Error Sending Command: " + e.message)
                    e.printStackTrace()
                    if (listener != null) {
                        listener!!.handleMqttException(context.getString(R.string.error_mqtt_subscription))
                    }
                }
            }
        }
    }

    private fun subscribeToTopics(topicFilters: Array<String>?) {
        topicFilters?.let {
            Timber.d("Subscribe to Topics: " + StringUtils.convertArrayToString(topicFilters))
            mqttClient?.let {
                if (isReady) {
                    try {
                        it.subscribe(topicFilters, MqttUtils.getQos(topicFilters.size), MqttUtils.getMqttMessageListeners(topicFilters.size, listener))
                    } catch (e: NullPointerException) {
                        Timber.e(e.message)
                    } catch (e: MqttException) {
                        if (listener != null) {
                            listener!!.handleMqttException("Exception while subscribing: " + e.message)
                        }
                    }
                }
            }
        }
    }

    companion object {
        // Use mqttQos=1 (at least once delivery), mqttQos=0 (at most once delivery) also supported.
        private val MQTT_QOS = 0
    }
}