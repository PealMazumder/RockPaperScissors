package com.example.rockpaperscissors

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import com.example.rockpaperscissors.databinding.ActivityMainBinding
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.util.*
import kotlin.text.Charsets.UTF_8


class MainActivity : AppCompatActivity() {
    private enum class GameChoice {
        ROCK, PAPER, SCISSORS;

        fun beats(other: GameChoice): Boolean =
            (this == ROCK && other == SCISSORS)
                    || (this == SCISSORS && other == PAPER)
                    || (this == PAPER && other == ROCK)
    }

    internal object CodenameGenerator {
        private val COLORS = arrayOf(
            "Red", "Orange", "Yellow", "Green", "Blue", "Indigo", "Violet", "Purple", "Lavender"
        )
        private val TREATS = arrayOf(
            "Cupcake", "Donut", "Eclair", "Froyo", "Gingerbread", "Honeycomb",
            "Ice Cream Sandwich", "Jellybean", "Kit Kat", "Lollipop", "Marshmallow", "Nougat",
            "Oreo", "Pie"
        )
        private val generator = Random()

        fun generate(): String {
            val color = COLORS[generator.nextInt(COLORS.size)]
            val treat = TREATS[generator.nextInt(TREATS.size)]
            return "$color $treat"
        }
    }

    private val STRATEGY = Strategy.P2P_STAR

    private lateinit var connectionsClient: ConnectionsClient

    private val REQUEST_CODE_REQUIRED_PERMISSIONS = 1

    private var opponentName: String? = null
    private var opponentEndpointId: String? = null
    private var opponentScore = 0
    private var opponentChoice: GameChoice? = null

    private var myCodeName: String = CodenameGenerator.generate()
    private var myScore = 0
    private var myChoice: GameChoice? = null

    private lateinit var binding: ActivityMainBinding

    private val payloadCallback: PayloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let {
                opponentChoice = GameChoice.valueOf(String(it, UTF_8))
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS
                && myChoice != null && opponentChoice != null
            ) {
                val mc = myChoice!!
                val oc = opponentChoice!!
                when {
                    mc.beats(oc) -> { // Win!
                        binding.status.text = "${mc.name} beats ${oc.name}"
                        myScore++
                    }
                    mc == oc -> { // Tie
                        binding.status.text = "You both chose ${mc.name}"
                    }
                    else -> { // Loss
                        binding.status.text = "${mc.name} loses to ${oc.name}"
                        opponentScore++
                    }
                }
                binding.score.text = "$myScore : $opponentScore"
                myChoice = null
                opponentChoice = null
                setGameControllerEnabled(true)
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            opponentName = "Opponent\n(${info.endpointName})"
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectionsClient.stopAdvertising()
                connectionsClient.stopDiscovery()
                opponentEndpointId = endpointId
                binding.opponentName.text = opponentName
                binding.status.text = "Connected"
                setGameControllerEnabled(true)
            }
        }

        override fun onDisconnected(endpointId: String) {
            resetGame()
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            connectionsClient.requestConnection(myCodeName, endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        connectionsClient = Nearby.getConnectionsClient(this)

        binding.myName.text = "You\n($myCodeName)"
        binding.findOpponent.setOnClickListener {
            startAdvertising()
            startDiscovery()
            binding.status.text = "Searching for opponents..."
            binding.findOpponent.visibility = View.GONE
            binding.disconnect.visibility = View.VISIBLE
        }
        // wire the controller buttons
        binding.apply {
            rock.setOnClickListener { sendGameChoice(GameChoice.ROCK) }
            paper.setOnClickListener { sendGameChoice(GameChoice.PAPER) }
            scissors.setOnClickListener { sendGameChoice(GameChoice.SCISSORS) }
        }
        binding.disconnect.setOnClickListener {
            opponentEndpointId?.let { connectionsClient.disconnectFromEndpoint(it) }
            resetGame()
        }

        resetGame()
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            myCodeName,
            packageName,
            connectionLifecycleCallback,
            options
        )
            .addOnSuccessListener {
                Log.d("Peal", "advertising!")
            }
            .addOnFailureListener {
                Log.d("Peal", "Unable to start advertising!")
            }
    }

    @CallSuper
    override fun onStart() {
        super.onStart()

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_CODE_REQUIRED_PERMISSIONS
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ),
                    REQUEST_CODE_REQUIRED_PERMISSIONS
                )
            }
        }
    }

    @CallSuper
    override fun onStop() {
        connectionsClient.apply {
            stopAdvertising()
            stopDiscovery()
            stopAllEndpoints()
        }
        resetGame()
        super.onStop()
    }

    @CallSuper
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val errMsg = "Cannot start without required permissions"
        if (requestCode == REQUEST_CODE_REQUIRED_PERMISSIONS) {
            grantResults.forEach {
                if (it == PackageManager.PERMISSION_DENIED) {
                    Toast.makeText(this, errMsg, Toast.LENGTH_LONG).show()
                    finish()
                    return
                }
            }
            recreate()
        }
    }

    private fun sendGameChoice(choice: GameChoice) {
        myChoice = choice
        connectionsClient.sendPayload(
            opponentEndpointId!!,
            Payload.fromBytes(choice.name.toByteArray(UTF_8))
        )
        binding.status.text = "You chose ${choice.name}"

        setGameControllerEnabled(false)
    }

    private fun setGameControllerEnabled(state: Boolean) {
        binding.apply {
            rock.isEnabled = state
            paper.isEnabled = state
            scissors.isEnabled = state
        }
    }

    private fun resetGame() {
        // reset data
        opponentEndpointId = null
        opponentName = null
        opponentChoice = null
        opponentScore = 0
        myChoice = null
        myScore = 0
        // reset state of views
        binding.disconnect.visibility = View.GONE
        binding.findOpponent.visibility = View.VISIBLE
        setGameControllerEnabled(false)
        binding.opponentName.text = "opponent\n(none yet)"
        binding.status.text = "..."
        binding.score.text = ":"
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(packageName, endpointDiscoveryCallback, options)
            .addOnSuccessListener {
                Log.d("Peal", "discovering!")
            }
            .addOnFailureListener {
                Log.d("Peal", "Unable to start discovering!")
            }
    }
}
