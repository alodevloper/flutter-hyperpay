package com.nyartech.hyperpay

import java.util.*
import android.app.Activity
import android.content.*
import android.os.*
import android.net.Uri
import android.util.Log

import androidx.annotation.NonNull
import androidx.browser.customtabs.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.embedding.engine.plugins.lifecycle.HiddenLifecycleReference

import com.oppwa.mobile.connect.exception.*
import com.oppwa.mobile.connect.payment.*
import com.oppwa.mobile.connect.payment.card.*
import com.oppwa.mobile.connect.payment.stcpay.STCPayPaymentParams
import com.oppwa.mobile.connect.payment.stcpay.STCPayVerificationOption
import com.oppwa.mobile.connect.provider.*
import com.oppwa.mobile.connect.threeds.ThreeDSConfig
import com.oppwa.mobile.connect.threeds.ThreeDS2Service

/** HyperpayPlugin */
class HyperpayPlugin : FlutterPlugin, MethodCallHandler, ITransactionListener, ActivityAware, ThreeDSWorkflowListener {
    private val TAG = "HyperpayPlugin"
    private val CUSTOM_TAB_PACKAGE_NAME = "com.android.chrome"

    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel
    private var channelResult: MethodChannel.Result? = null

    private var mActivity: Activity? = null
    private var paymentProvider: OppPaymentProvider? = null
    private var intent: Intent? = null

    // Get the checkout ID from the endpoint on your server
    private var checkoutID = ""
    private var paymentMode = ""

    // Card details
    private var brand = Brand.UNKNOWN
    private var cardHolder: String = ""
    private var cardNumber: String = ""
    private var expiryMonth: String = ""
    private var expiryYear: String = ""
    private var cvv: String = ""

    // stc pay phone number
    private var phoneNumber: String = ""
    private var shopperResultUrl: String = ""

    private var mCustomTabsClient: CustomTabsClient? = null
    private var mCustomTabsIntent: CustomTabsIntent? = null
    private var hiddenLifecycleReference: HiddenLifecycleReference? = null

    // 3DS 2 Configuration
    private var threeDSConfig: ThreeDSConfig? = null

    // used to store the result URL from ChromeCustomTabs intent
    private var redirectData = ""

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if(event == Lifecycle.Event.ON_RESUME && (redirectData.isEmpty() && mCustomTabsIntent != null)) {
            Log.d(TAG, "Cancelling.")
            mCustomTabsIntent = null
            success("canceled")
        }
    }

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "plugins.nyartech.com/hyperpay")
        channel.setMethodCallHandler(this)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        mActivity = binding.activity
        hiddenLifecycleReference = (binding.lifecycle as HiddenLifecycleReference)
        hiddenLifecycleReference?.lifecycle?.addObserver(lifecycleObserver)

        // Check if 3DS transaction was killed by system
        checkForKilledTransaction()

        val activity = mActivity
        if (activity != null) {
            // Remove any underscores from the application ID for Uri parsing
            shopperResultUrl = activity.packageName.replace("_", "")
            shopperResultUrl += ".payments"

            binding.addOnNewIntentListener {
                if (it.scheme?.equals(shopperResultUrl, ignoreCase = true) == true) {
                    redirectData = it.scheme.toString()
                    Log.d(TAG, "Success, redirecting to app...")
                    success("success")
                }
                true
            }
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        hiddenLifecycleReference?.lifecycle?.removeObserver(lifecycleObserver)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        mActivity = binding.activity
        hiddenLifecycleReference = (binding.lifecycle as HiddenLifecycleReference)
        hiddenLifecycleReference?.lifecycle?.addObserver(lifecycleObserver)
        
        // Check if 3DS transaction was killed by system after config change
        checkForKilledTransaction()
    }

    override fun onDetachedFromActivity() {
        val activity = mActivity
        if (intent != null && activity != null) {
            activity.stopService(intent)
        }

        hiddenLifecycleReference?.lifecycle?.removeObserver(lifecycleObserver)
        hiddenLifecycleReference = null
        mActivity = null
    }

    /**
     * Check if 3DS transaction was killed by system due to low memory
     */
    private fun checkForKilledTransaction() {
        if (ThreeDS2Service.wasTransactionKilled()) {
            Log.w(TAG, "3DS transaction was killed by system. Payment status should be verified.")
            // Notify Flutter side that transaction state was lost
            handler.post {
                channelResult?.error(
                    "3DS_KILLED",
                    "3DS transaction was interrupted by system. Please verify payment status.",
                    null
                )
            }
        }
    }

    // Handling result options
    private val handler: Handler = Handler(Looper.getMainLooper())

    private fun success(result: Any?) {
        handler.post { channelResult?.success(result) }
    }

    private fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
        handler.post { channelResult?.error(errorCode, errorMessage, errorDetails) }
    }

    private var cctConnection: CustomTabsServiceConnection = object : CustomTabsServiceConnection() {
        override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
            mCustomTabsClient = client
            mCustomTabsClient?.warmup(0L)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            mCustomTabsClient = null
        }
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "setup_service" -> {
                val activity = mActivity

                if (activity == null) {
                    result.error("0.4", "Activity not attached (missing in setup_service call)", null)
                    return
                }

                try {
                    val args: Map<String, Any> = call.arguments as Map<String, Any>
                    paymentMode = args["mode"] as String
                    var providerMode = Connect.ProviderMode.TEST

                    if(paymentMode == "LIVE") {
                        providerMode = Connect.ProviderMode.LIVE
                    }

                    // Initialize payment provider
                    paymentProvider = OppPaymentProvider(activity.application, providerMode)

                    // Initialize 3DS Config
                    threeDSConfig = ThreeDSConfig.Builder()
                        // Add any custom 3DS configuration here
                        // .setAppearance(appearance)
                        // .setAuthenticationRequestParameters(authParams)
                        .build()

                    // Set the 3DS listener
                    paymentProvider?.setThreeDSWorkflowListener(this)

                    // Bind CustomTabs service
                    CustomTabsClient.bindCustomTabsService(activity, CUSTOM_TAB_PACKAGE_NAME, cctConnection)

                    Log.d(TAG, "Payment mode is set to $paymentMode with 3DS 2 enabled")
                    result.success(null)
                } catch (e: Exception) {
                    e.printStackTrace()
                    result.error("SETUP_ERROR", e.message, e.stackTrace.toString())
                }
            }
            "start_payment_transaction" -> {
                channelResult = result

                val args: Map<String, Any> = call.arguments as Map<String, Any>
                checkoutID = (args["checkoutID"] as String?)!!
                brand = Brand.valueOf(args["brand"].toString())

                when (brand) {
                    Brand.UNKNOWN -> result.error(
                        "0.1",
                        "Please provide a valid brand",
                        ""
                    )
                    Brand.STCPAY -> {
                        checkoutID = (args["checkoutID"] as String?)!!

                        val paymentParams = STCPayPaymentParams(
                            checkoutID,
                            STCPayVerificationOption.MOBILE_PHONE,
                        )

                        // Set shopper result URL
                        paymentParams.shopperResultUrl = "$shopperResultUrl://result"

                        try {
                            val transaction = Transaction(paymentParams)
                            paymentProvider?.submitTransaction(transaction, this)
                        } catch (e: PaymentException) {
                            result.error(
                                "0.2",
                                e.localizedMessage,
                                ""
                            )
                        }
                    }
                    else -> {
                        val card: Map<String, Any> = args["card"] as Map<String, Any>
                        cardHolder = (card["holder"] as String?)!!
                        cardNumber = (card["number"] as String?)!!
                        expiryMonth = (card["expiryMonth"] as String?)!!
                        expiryYear = (card["expiryYear"] as String?)!!
                        cvv = (card["cvv"] as String?)!!

                        var validator: String? = checkCreditCardValid(result)
                        if(validator != null) {
                            result.error("1.1", validator, "")
                            return
                        }

                        val paymentParams: PaymentParams = CardPaymentParams(
                            checkoutID,
                            brand.name,
                            cardNumber,
                            cardHolder,
                            expiryMonth,
                            expiryYear,
                            cvv
                        )

                        // Set shopper result URL
                        paymentParams.shopperResultUrl = "$shopperResultUrl://result"

                        try {
                            val transaction = Transaction(paymentParams)
                            // 3DS 2 authentication is handled automatically within this call
                            paymentProvider?.submitTransaction(transaction, this)
                        } catch (e: PaymentException) {
                            result.error(
                                "0.3",
                                e.localizedMessage,
                                ""
                            )
                        }
                    }
                }
            }
            "check_payment_card" -> {
                val args: Map<String, Any> = call.arguments as Map<String, Any>
                brand = Brand.valueOf(args["brand"].toString())
                val card: Map<String, Any> = args["card"] as Map<String, Any>
                cardHolder = (card["holder"] as String?)!!
                cardNumber = (card["number"] as String?)!!
                expiryMonth = (card["expiryMonth"] as String?)!!
                expiryYear = (card["expiryYear"] as String?)!!
                cvv = (card["cvv"] as String?)!!

                var validator: String? = checkCreditCardValid(result)
                if(validator != null) {
                    result.success(validator)
                } else {
                    result.success(null)
                }
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    /**
     * This function checks the provided card params and return
     * a PlatformException to Flutter if any are not valid.
     */
    private fun checkCreditCardValid(result: Result): String? {
        if (!CardPaymentParams.isNumberValid(cardNumber, true)) {
            return "Card number is not valid for brand ${brand.name}"
        }
        if (!CardPaymentParams.isHolderValid(cardHolder)) {
            return "Holder name is not valid"
        }
        if (!CardPaymentParams.isExpiryMonthValid(expiryMonth)) {
            return "Expiry month is not valid"
        }
        if (!CardPaymentParams.isExpiryYearValid(expiryYear)) {
            return "Expiry year is not valid"
        }
        if (!CardPaymentParams.isCvvValid(cvv)) {
            return "CVV is not valid"
        }
        return null
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun transactionCompleted(transaction: Transaction) {
        try {
            if (transaction.transactionType == TransactionType.SYNC) {
                // Send request to your server to obtain transaction status
                success("synchronous")
            } else {
                val uri = Uri.parse(transaction.redirectUrl)
                redirectData = ""

                val activity = mActivity 

                if (activity == null) {
                    error("0.5", "Activity not attached when starting redirect URL", null)
                    return
                }

                val session = mCustomTabsClient?.newSession(object : CustomTabsCallback() {
                    override fun onNavigationEvent(navigationEvent: Int, extras: Bundle?) {
                        Log.w(TAG, "onNavigationEvent: Code = $navigationEvent")
                        when (navigationEvent) {
                            TAB_HIDDEN -> {
                                if (redirectData.isEmpty()) {
                                    mCustomTabsIntent = null
                                    success("canceled")
                                }
                            }
                        }
                    }
                })

                val builder = CustomTabsIntent.Builder(session)
                mCustomTabsIntent = builder.build()
                activity.intent?.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                mCustomTabsIntent?.intent?.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                mCustomTabsIntent?.launchUrl(activity, uri) 
            }
        } catch (e: Exception) {
            e.printStackTrace()
            error("0.6", "${e.message}", null)
        }
    }

    override fun transactionFailed(transaction: Transaction, error: PaymentError) {
        error(
            "${error.errorCode}",
            error.errorMessage,
            "${error.errorInfo}"
        )
    }

    // ========== 3DS 2 Workflow Listener Implementation ==========
    
    /**
     * Called when 3DS challenge screen needs to be displayed
     * @return Activity instance for displaying the challenge
     */
    override fun onThreeDSChallengeRequired(): Activity {
        return mActivity ?: throw IllegalStateException(
            "Activity is required for 3DS challenge but is null."
        )
    }

    /**
     * Called to get 3DS configuration
     * @return ThreeDSConfig instance with customization options
     */
    override fun onThreeDSConfigRequired(): ThreeDSConfig {
        if (threeDSConfig == null) {
            Log.w(TAG, "ThreeDSConfig was null, creating default config")
            threeDSConfig = ThreeDSConfig.Builder().build()
        }
        return threeDSConfig!!
    }
}
