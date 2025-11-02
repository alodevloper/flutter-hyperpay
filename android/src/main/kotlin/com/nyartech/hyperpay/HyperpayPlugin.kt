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

    // This is the mutable property causing the compilation error
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

    private var mCustomTabsClient: CustomTabsClient? = null;
    private var mCustomTabsIntent: CustomTabsIntent? = null;
    private var hiddenLifecycleReference: HiddenLifecycleReference? = null;

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
        mActivity = binding.activity;
        hiddenLifecycleReference = (binding.lifecycle as HiddenLifecycleReference)
        hiddenLifecycleReference?.lifecycle?.addObserver(lifecycleObserver)

        // Capture activity for safe use in the null-check section
        val activity = mActivity
        if (activity != null) {
            // Remove any underscores from the application ID for Uri parsing
            // NOTE: It's important to add your application ID as the scheme, followed by ".payments"
            // without any underscores.
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
        hiddenLifecycleReference = (binding.lifecycle as HiddenLifecycleReference)
        hiddenLifecycleReference?.lifecycle?.addObserver(lifecycleObserver)
    }

    override fun onDetachedFromActivity() {
        // Capture mActivity to a local val before using it
        val activity = mActivity
        if (intent != null && activity != null) {
            activity.stopService(intent)
        }

        hiddenLifecycleReference?.lifecycle?.removeObserver(lifecycleObserver)
        hiddenLifecycleReference = null
        mActivity = null
    }

    // Handling result options
    private val handler: Handler = Handler(Looper.getMainLooper())

    private fun success(result: Any?) {
        handler.post { channelResult!!.success(result) }
    }

    private fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
        handler.post { channelResult!!.error(errorCode, errorMessage, errorDetails) }
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
                // Capture mActivity to a local val for safe smart-casting
                val activity = mActivity

                if (activity == null) {
                    result.error("0.4", "Activity not attached (missing in setup_service call)", null)
                    return
                }

                try {
                    val args: Map<String, Any> = call.arguments as Map<String, Any>

                    paymentMode = args["mode"] as String
                    var providerMode = Connect.ProviderMode.TEST;

                    if(paymentMode == "LIVE") {
                        providerMode = Connect.ProviderMode.LIVE;
                    }

                    // Use the safe 'activity' variable
                    paymentProvider = OppPaymentProvider(activity.application, providerMode);

                    // Set the 3DS listener
                    paymentProvider!!.setThreeDSWorkflowListener{activity}

                    // Bind CustomTabs service with the current app activity
                    // Use the safe 'activity' variable
                    CustomTabsClient.bindCustomTabsService(activity, CUSTOM_TAB_PACKAGE_NAME, cctConnection);

                    Log.d(TAG, "Payment mode is set to $paymentMode")
                    result.success(null)
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()

                    result.error("", e.message, e.stackTrace)
                }
            }
            "start_payment_transaction" -> {
                channelResult = result

                val args: Map<String, Any> = call.arguments as Map<String, Any>
                checkoutID = (args["checkoutID"] as String?)!!
                brand = Brand.valueOf(args["brand"].toString())

                when (brand) {
                    // If the brand is not provided it returns an error result
                    Brand.UNKNOWN -> result.error(
                                "0.1",
                                "Please provide a valid brand",
                                ""
                    )
                    Brand.STCPAY -> {
                            // stc-pay
                            checkoutID = (args["checkoutID"] as String?)!!
                        //  phoneNumber = (args["phoneNumber"] as String?)!!

                            val paymentParams = STCPayPaymentParams(
                                    checkoutID,
                                    STCPayVerificationOption.MOBILE_PHONE,
                            );
                            // add in customer.mobile when getCheckoutId
                            // paymentParams.mobilePhoneNumber = phoneNumber;


                            //Set shopper result URL
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

                        var validator:String? =  checkCreditCardValid(result)
                        if(validator != null){
                            result.error("1.1",validator,"")
                            return;
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

                        //Set shopper result URL
                        paymentParams.shopperResultUrl = "$shopperResultUrl://result"

                        try {
                            val transaction = Transaction(paymentParams)
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

                var validator:String? =  checkCreditCardValid(result)
                if(validator != null){
                    result.success(validator)
                }else{
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
     * */
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
        return null;
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

                // 💥 FIX: Capture mActivity into an immutable local variable 'activity'
                // This resolves the smart-cast compiler error.
                val activity = mActivity 

                if (activity == null) {
                     // Handle the case where the activity is unexpectedly null
                     error("0.5", "Activity not attached when starting redirect URL", null)
                     return
                }

                val session = mCustomTabsClient?.newSession(object : CustomTabsCallback() {
                    override fun onNavigationEvent(navigationEvent: Int, extras: Bundle?) {
                        val w = Log.w(TAG, "onNavigationEvent: Code = $navigationEvent")
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
                // Use the safe 'activity' variable instead of mActivity!!
                activity.intent?.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                mCustomTabsIntent?.intent?.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                // Use the safe 'activity' variable instead of mActivity!!
                mCustomTabsIntent?.launchUrl(activity, uri) 
            }
        } catch (e: Exception) {
            e.printStackTrace()

            // Display error
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

    override fun onThreeDSChallengeRequired(): Activity {
        // 💥 FIX: Safe way to get the Activity, throwing an exception if null is necessary here 
        // because the Oppwa SDK requires a non-null Activity return.
        return mActivity ?: throw IllegalStateException("Activity is required for 3DS challenge but is null.")
    }
}
