package fr.bmartel.bboxapi.router

import com.github.kittinunf.fuel.core.*
import com.github.kittinunf.fuel.gson.gsonDeserializerOf
import com.github.kittinunf.result.Result
import com.github.kittinunf.result.flatMapError
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import fr.bmartel.bboxapi.router.model.*
import java.net.HttpCookie
import java.util.*
import java.util.regex.Pattern
import kotlin.concurrent.schedule

class BboxApiRouter {

    var password: String = ""
    var bboxId: String = ""

    var authenticated: Boolean = false

    class BboxAuthException(error: BboxException) : Exception("Bbox authentication failed : ${error.exception.toString()}") {
        var error = error
    }

    /**
     * whether or not client is blocked by too many attempts.
     */
    var blocked: Boolean = false
        get() = blockedUntil.after(Date())

    /**
     * date until which client is blocked.
     */
    var blockedUntil: Date = Date()

    /**
     * number of login attempts.
     */
    var attempts: Int = 0

    val manager = FuelManager()

    init {
        manager.basePath = "http://bbox.lan/api/v1"
    }

    fun setBasePath(basePath: String) {
        manager.basePath = basePath
    }

    private fun buildLoginRequest(): Request {
        return manager.request(method = Method.POST, path = "/login", param = listOf("password" to password, "remember" to 1))
    }

    private fun buildSummaryRequest(): Request {
        return manager.request(method = Method.GET, path = "/summary")
    }

    private fun buildXdslRequest(): Request {
        return manager.request(method = Method.GET, path = "/wan/xdsl")
    }

    private fun buildHostRequest(): Request {
        return manager.request(method = Method.GET, path = "/hosts")
    }

    private fun buildWanIpInfoRequest(): Request {
        return manager.request(method = Method.GET, path = "/wan/ip")
    }

    private fun buildDeviceInfoRequest(): Request {
        return manager.request(method = Method.GET, path = "/device")
    }

    private fun buildVoipRequest(): Request {
        return manager.request(method = Method.GET, path = "/voip")
    }

    private fun buildWirelessRequest(): Request {
        return manager.request(method = Method.GET, path = "/wireless")
    }

    private fun buildCallLogsRequest(line: Line): Request {
        return manager.request(method = Method.GET, path = "/voip/fullcalllog/${if (line == Line.LINE1) 1 else 2}")
    }

    private fun buildWifiStateRequest(state: Boolean): Request {
        return manager.request(method = Method.PUT, path = "/wireless?radio.enable=${if (state) 1 else 0}")
    }

    private fun buildDisplayStateRequest(state: Boolean): Request {
        return manager.request(method = Method.PUT, path = "/device/display?luminosity=${if (state) 100 else 0}")
    }

    private fun buildVoipDialRequest(line: Line, phoneNumber: String): Request {
        return manager.request(method = Method.PUT, path = "/voip/dial?line=${if (line == Line.LINE1) 1 else 2}&number=$phoneNumber")
    }

    private fun buildTokenRequest(): Request {
        return manager.request(method = Method.GET, path = "/device/token")
    }

    private fun buildRebootRequest(btoken: String?): Request {
        return manager.request(method = Method.POST, path = "/device/reboot?btoken=$btoken")
    }

    private fun buildGetAclRequest(): Request {
        return manager.request(method = Method.GET, path = "/wireless/acl")
    }

    private fun buildSetWifiMacFilterRequest(state: Boolean): Request {
        return manager.request(method = Method.PUT, path = "/wireless/acl?enable=${if (state) 1 else 0}")
    }

    private fun buildDeleteAclRequest(ruleIndex: Int): Request {
        return manager.request(method = Method.DELETE, path = "/wireless/acl/rules/$ruleIndex")
    }

    private fun buildUpdateAclRequest(ruleIndex: Int, rule: MacFilterRule): Request {
        val data = listOf(
                "enable" to (if (rule.enable) 1 else 0),
                "macaddress" to rule.macaddress,
                "device" to (if (rule.ip == "") -1 else rule.ip)
        )
        return manager.request(method = Method.PUT, path = "/wireless/acl/rules/$ruleIndex", param = data)
    }

    private fun buildCreateAclRequest(btoken: String?, rule: MacFilterRule): Request {
        val data = listOf(
                "enable" to (if (rule.enable) 1 else 0),
                "macaddress" to rule.macaddress,
                "device" to (if (rule.ip == "") -1 else rule.ip)
        )
        return manager.request(method = Method.POST, path = "/wireless/acl/rules?btoken=$btoken", param = data)
    }

    private fun buildLogoutRequest(): Request {
        return manager.request(method = Method.POST, path = "/logout")
    }

    private fun buildStartRecoveryRequest(): Request {
        return manager.request(method = Method.POST, path = "/password-recovery")
    }

    private fun buildVerifyRecoveryRequest(): Request {
        return manager.request(method = Method.GET, path = "/password-recovery/verify")
    }

    private fun buildResetPasswordRequest(btoken: String?): Request {
        val data = listOf(
                "password" to password
        )
        return manager.request(method = Method.POST, path = "/reset-password?btoken=$btoken", param = data)
    }

    private fun buildOauthAuthorizeRequest(oauthParam: OauthParam): Request {
        var scopeStr = ""
        oauthParam.scope.map { scopeStr += "${it.field} " }
        val data = mutableListOf(
                "grant_type" to oauthParam.grantType.field,
                "client_id" to oauthParam.clientId,
                "client_secret" to oauthParam.clientSecret,
                "response_type" to oauthParam.responseType.field
        )
        return manager.request(method = Method.POST, path = "/oauth/authorize", param = data)
    }

    private fun onAuthenticationSuccess(response: Response) {
        response.headers["Set-Cookie"]?.flatMap { HttpCookie.parse(it) }?.find { it.name == "BBOX_ID" }?.let {
            bboxId = it.value
            authenticated = true
            attempts = 0
            blockedUntil = Date()
        }
    }

    inline fun <reified T> Gson.fromJson(json: String) = this.fromJson<T>(json, object : TypeToken<T>() {}.type)

    private inline fun <reified T : Any> authenticateAndExecute(request: Request, noinline handler: (Request, Response, Result<T, FuelError>) -> Unit, json: Boolean = true) {
        authenticate { authResult ->
            val (req, res, exception, cookie) = authResult
            if (exception != null) {
                handler(req, res, Result.error(Exception("failure")).flatMapError {
                    Result.error(FuelError(exception))
                })
            } else {
                bboxId = cookie ?: ""
                if (json) {
                    request.header(pairs = *arrayOf("Cookie" to "BBOX_ID=$bboxId")).responseObject(deserializer = gsonDeserializerOf(), handler = handler)
                } else {
                    request.header(pairs = *arrayOf("Cookie" to "BBOX_ID=$bboxId")).responseString(handler = handler as (Request, Response, Result<*, FuelError>) -> Unit)
                }
            }
        }
    }

    private inline fun <reified T : Any> authenticateAndExecute(request: Request, handler: Handler<T>, json: Boolean = true) {
        authenticate { authResult ->
            val (req, res, exception, cookie) = authResult
            if (exception != null) {
                handler.failure(req, res, Result.error(FuelError(exception)).error)
            } else {
                bboxId = cookie ?: ""
                if (json) {
                    request.header(pairs = *arrayOf("Cookie" to "BBOX_ID=$bboxId")).responseObject(deserializer = gsonDeserializerOf(), handler = handler)
                } else {
                    request.header(pairs = *arrayOf("Cookie" to "BBOX_ID=$bboxId")).responseString(handler = handler as Handler<String>)
                }
            }
        }
    }

    private inline fun <reified T : Any> processSecureApi(request: Request, handler: Handler<T>, json: Boolean = true) {
        if (!authenticated) {
            authenticateAndExecute(request, handler, json = json)
        } else {
            if (json) {
                request.header(pairs = *arrayOf("Cookie" to "BBOX_ID=$bboxId")).responseObject<T>(deserializer = gsonDeserializerOf()) { req, res, result ->
                    if (res.statusCode == 401) {
                        authenticateAndExecute(request = request, handler = handler, json = json)
                    } else {
                        handler.success(req, res, result.get())
                    }
                }
            } else {
                request.header(pairs = *arrayOf("Cookie" to "BBOX_ID=$bboxId")).responseString { req, res, result ->
                    if (res.statusCode == 401) {
                        authenticateAndExecute(request = request, handler = handler, json = json)
                    } else {
                        handler.success(req, res, result.get() as T)
                    }
                }
            }
        }
    }

    private inline fun <reified T : Any> processSecureApi(request: Request, noinline handler: (Request, Response, Result<T, FuelError>) -> Unit, json: Boolean = true) {
        if (!authenticated) {
            authenticateAndExecute(request, handler, json = json)
        } else {
            if (json) {
                request.header(pairs = *arrayOf("Cookie" to "BBOX_ID=$bboxId")).responseObject<T>(deserializer = gsonDeserializerOf()) { req, res, result ->
                    if (res.statusCode == 401) {
                        authenticateAndExecute(request = request, handler = handler, json = json)
                    } else {
                        handler(req, res, result)
                    }
                }
            } else {
                request.header(pairs = *arrayOf("Cookie" to "BBOX_ID=$bboxId")).responseString { req, res, result ->
                    if (res.statusCode == 401) {
                        authenticateAndExecute(request = request, handler = handler, json = json)
                    } else {
                        handler(req, res, result as Result<T, FuelError>)
                    }
                }
            }
        }
    }

    private inline fun <reified T : Any> authenticateAndExecuteSync(request: Request, json: Boolean = true): Triple<Request, Response, Result<T, FuelError>> {
        val (req, res, exception, cookie) = authenticateSync()
        if (exception != null) {
            return Triple(req, res, Result.error(Exception("failure")).flatMapError {
                Result.error(ex = FuelError(exception = exception))
            })
        } else {
            bboxId = cookie ?: ""
            if (json) {
                return request.header(pairs = *arrayOf("Cookie" to "BBOX_ID=$bboxId")).responseObject(deserializer = gsonDeserializerOf())
            } else {
                return request.header(pairs = *arrayOf("Cookie" to "BBOX_ID=$bboxId")).responseString() as Triple<Request, Response, Result<T, FuelError>>
            }
        }
    }

    private inline fun <reified T : Any> processSecureApiSync(request: Request, json: Boolean = true): Triple<Request, Response, Result<T, FuelError>> {
        if (!authenticated) {
            return authenticateAndExecuteSync(request = request, json = json)
        } else {
            val triple = if (json) {
                request.header(pairs = *arrayOf("Cookie" to "BBOX_ID=$bboxId")).responseObject<T>(gsonDeserializerOf())
            } else {
                request.header(pairs = *arrayOf("Cookie" to "BBOX_ID=$bboxId")).responseString()
            }

            return if (triple.second.statusCode == 401) {
                authenticateAndExecuteSync(request = request)
            } else {
                triple as Triple<Request, Response, Result<T, FuelError>>
            }
        }
    }

    private fun processAuth(request: Request, response: Response, result: Result<*, FuelError>): AuthResult {
        when (result) {
            is Result.Failure -> {
                authenticated = false
                attempts++
                var authError: BboxException? = null
                var exception: Exception?
                if (response.data.isNotEmpty()) {
                    try {
                        authError = Gson().fromJson(String(response.data), BboxException::class.java)
                        exception = BboxAuthException(authError)
                    } catch (e: JsonSyntaxException) {
                        exception = e
                    }
                } else {
                    exception = result.getException().exception
                }
                if (authError?.exception?.code?.toInt() == 429 &&
                        authError.exception?.errors != null &&
                        authError.exception?.errors?.isNotEmpty()!!) {
                    val pattern = Pattern.compile("(\\d+) attempts, retry after (\\d+) seconds")
                    val matcher = pattern.matcher(authError.exception?.errors?.get(0)?.reason)
                    if (matcher.find()) {
                        val calendar = Calendar.getInstance() // gets a calendar using the default time zone and locale.
                        calendar.add(Calendar.SECOND, matcher.group(2).toInt())
                        blockedUntil = calendar.time
                    }
                }
                return AuthResult(request = request, response = response, exception = exception, bboxid = null)
            }
            is Result.Success -> {
                onAuthenticationSuccess(response)
                return AuthResult(request = request, response = response, exception = null, bboxid = bboxId)
            }
        }
    }

    fun authenticate(handler: (AuthResult) -> Unit) {
        buildLoginRequest().response { request, response, result ->
            handler(processAuth(request = request, response = response, result = result))
        }
    }

    fun authenticateSync(): AuthResult {
        val (request, response, result) = buildLoginRequest().responseString()
        return processAuth(request = request, response = response, result = result)
    }

    fun getSummary(handler: (Request, Response, Result<List<Summary>, FuelError>) -> Unit) {
        buildSummaryRequest().responseObject(gsonDeserializerOf(), handler)
    }

    fun getSummary(handler: Handler<List<Summary>>) {
        buildSummaryRequest().responseObject(gsonDeserializerOf(), handler)
    }

    fun getSummarySync(): Triple<Request, Response, Result<List<Summary>, FuelError>> {
        return buildSummaryRequest().responseObject(gsonDeserializerOf())
    }

    fun getXdslInfo(handler: (Request, Response, Result<List<Wan>, FuelError>) -> Unit) {
        buildXdslRequest().responseObject(gsonDeserializerOf(), handler)
    }

    fun getXdslInfo(handler: Handler<List<Wan>>) {
        buildXdslRequest().responseObject(gsonDeserializerOf(), handler)
    }

    fun getXdslInfoSync(): Triple<Request, Response, Result<List<Wan>, FuelError>> {
        return buildXdslRequest().responseObject(gsonDeserializerOf())
    }

    fun getHosts(handler: (Request, Response, Result<List<Hosts>, FuelError>) -> Unit) {
        buildHostRequest().responseObject(gsonDeserializerOf(), handler)
    }

    fun getHosts(handler: Handler<List<Hosts>>) {
        buildHostRequest().responseObject(gsonDeserializerOf(), handler)
    }

    fun getHostsSync(): Triple<Request, Response, Result<List<Hosts>, FuelError>> {
        return buildHostRequest().responseObject(gsonDeserializerOf())
    }

    fun getWanIpInfo(handler: (Request, Response, Result<List<Wan>, FuelError>) -> Unit) {
        buildWanIpInfoRequest().responseObject(gsonDeserializerOf(), handler)
    }

    fun getWanIpInfo(handler: Handler<List<Wan>>) {
        buildWanIpInfoRequest().responseObject(gsonDeserializerOf(), handler)
    }

    fun getWanIpInfoSync(): Triple<Request, Response, Result<List<Wan>, FuelError>> {
        return buildWanIpInfoRequest().responseObject(gsonDeserializerOf())
    }

    fun getDeviceInfo(handler: (Request, Response, Result<List<Device>, FuelError>) -> Unit) {
        buildDeviceInfoRequest().responseObject(gsonDeserializerOf(), handler)
    }

    fun getDeviceInfo(handler: Handler<List<Device>>) {
        buildDeviceInfoRequest().responseObject(gsonDeserializerOf(), handler)
    }

    fun getDeviceInfoSync(): Triple<Request, Response, Result<List<Device>, FuelError>> {
        return buildDeviceInfoRequest().responseObject(gsonDeserializerOf())
    }

    fun getVoipInfo(handler: (Request, Response, Result<List<Voip>, FuelError>) -> Unit) {
        processSecureApi(request = buildVoipRequest(), handler = handler)
    }

    fun getVoipInfo(handler: Handler<List<Voip>>) {
        processSecureApi(request = buildVoipRequest(), handler = handler)
    }

    fun getVoipInfoSync(): Triple<Request, Response, Result<List<Voip>, FuelError>> {
        return processSecureApiSync(request = buildVoipRequest())
    }

    fun getWirelessInfo(handler: (Request, Response, Result<List<Wireless>, FuelError>) -> Unit) {
        processSecureApi(request = buildWirelessRequest(), handler = handler)
    }

    fun getWirelessInfo(handler: Handler<List<Wireless>>) {
        processSecureApi(request = buildWirelessRequest(), handler = handler)
    }

    fun getWirelessInfoSync(): Triple<Request, Response, Result<List<Wireless>, FuelError>> {
        return processSecureApiSync(request = buildWirelessRequest())
    }

    fun getCallLogs(line: Line, handler: (Request, Response, Result<List<CallLog>, FuelError>) -> Unit) {
        processSecureApi(request = buildCallLogsRequest(line), handler = handler)
    }

    fun getCallLogs(line: Line, handler: Handler<List<CallLog>>) {
        processSecureApi(request = buildCallLogsRequest(line), handler = handler)
    }

    fun getCallLogsSync(line: Line): Triple<Request, Response, Result<List<CallLog>, FuelError>> {
        return processSecureApiSync(request = buildCallLogsRequest(line))
    }

    fun setWifiState(state: Boolean, handler: (Request, Response, Result<String, FuelError>) -> Unit) {
        processSecureApi(request = buildWifiStateRequest(state), handler = handler, json = false)
    }

    fun setWifiState(state: Boolean, handler: Handler<String>) {
        processSecureApi(request = buildWifiStateRequest(state), handler = handler, json = false)
    }

    fun setWifiStateSync(state: Boolean): Triple<Request, Response, Result<String, FuelError>> {
        return processSecureApiSync(request = buildWifiStateRequest(state), json = false)
    }

    fun setDisplayState(state: Boolean, handler: (Request, Response, Result<String, FuelError>) -> Unit) {
        processSecureApi(request = buildDisplayStateRequest(state), handler = handler, json = false)
    }

    fun setDisplayState(state: Boolean, handler: Handler<String>) {
        processSecureApi(request = buildDisplayStateRequest(state), handler = handler, json = false)
    }

    fun setDisplayStateSync(state: Boolean): Triple<Request, Response, Result<String, FuelError>> {
        return processSecureApiSync(request = buildDisplayStateRequest(state), json = false)
    }

    fun voipDial(line: Line, phoneNumber: String, handler: (Request, Response, Result<String, FuelError>) -> Unit) {
        processSecureApi(
                request = buildVoipDialRequest(line, phoneNumber),
                handler = handler,
                json = false)
    }

    fun voipDial(line: Line, phoneNumber: String, handler: Handler<String>) {
        processSecureApi(
                request = buildVoipDialRequest(line, phoneNumber),
                handler = handler,
                json = false)
    }

    fun voipDialSync(line: Line, phoneNumber: String): Triple<Request, Response, Result<String, FuelError>> {
        return processSecureApiSync(
                request = buildVoipDialRequest(line, phoneNumber),
                json = false)
    }

    fun getToken(handler: (Request, Response, Result<List<Token>, FuelError>) -> Unit) {
        processSecureApi(request = buildTokenRequest(), handler = handler)
    }

    fun getToken(handler: Handler<List<Token>>) {
        processSecureApi(request = buildTokenRequest(), handler = handler)
    }

    fun getTokenSync(): Triple<Request, Response, Result<List<Token>, FuelError>> {
        return processSecureApiSync(request = buildTokenRequest())
    }

    fun reboot(handler: (Request, Response, Result<String, FuelError>) -> Unit) {
        getToken { _, _, result ->
            processSecureApi(
                    request = buildRebootRequest(btoken = result.get()[0].device?.token),
                    handler = handler,
                    json = false)
        }
    }

    fun reboot(handler: Handler<String>) {
        getToken { _, _, result ->
            processSecureApi(
                    request = buildRebootRequest(btoken = result.get()[0].device?.token),
                    handler = handler,
                    json = false)
        }
    }

    fun rebootSync(): Triple<Request, Response, Result<String, FuelError>> {
        val (_, _, result) = getTokenSync()
        return processSecureApiSync(request = buildRebootRequest(btoken = result.get()[0].device?.token), json = false)
    }

    fun getWifiMacFilter(handler: (Request, Response, Result<List<Acl>, FuelError>) -> Unit) {
        processSecureApi(request = buildGetAclRequest(), handler = handler)
    }

    fun getWifiMacFilter(handler: Handler<List<Acl>>) {
        processSecureApi(request = buildGetAclRequest(), handler = handler)
    }

    fun getWifiMacFilterSync(): Triple<Request, Response, Result<List<Acl>, FuelError>> {
        return processSecureApiSync(request = buildGetAclRequest())
    }

    fun setWifiMacFilter(state: Boolean, handler: (Request, Response, Result<String, FuelError>) -> Unit) {
        processSecureApi(request = buildSetWifiMacFilterRequest(state), handler = handler, json = false)
    }

    fun setWifiMacFilter(state: Boolean, handler: Handler<String>) {
        processSecureApi(request = buildSetWifiMacFilterRequest(state), handler = handler, json = false)
    }

    fun setWifiMacFilterSync(state: Boolean): Triple<Request, Response, Result<String, FuelError>> {
        return processSecureApiSync(request = buildSetWifiMacFilterRequest(state), json = false)
    }

    fun deleteMacFilterRule(ruleIndex: Int, handler: (Request, Response, Result<String, FuelError>) -> Unit) {
        processSecureApi(request = buildDeleteAclRequest(ruleIndex), handler = handler, json = false)
    }

    fun deleteMacFilterRule(ruleIndex: Int, handler: Handler<String>) {
        processSecureApi(request = buildDeleteAclRequest(ruleIndex), handler = handler, json = false)
    }

    fun deleteMacFilterRuleSync(ruleIndex: Int): Triple<Request, Response, Result<String, FuelError>> {
        return processSecureApiSync(request = buildDeleteAclRequest(ruleIndex), json = false)
    }

    fun updateMacFilterRule(ruleIndex: Int, rule: MacFilterRule, handler: (Request, Response, Result<String, FuelError>) -> Unit) {
        processSecureApi(request = buildUpdateAclRequest(ruleIndex, rule), handler = handler, json = false)
    }

    fun updateMacFilterRule(ruleIndex: Int, rule: MacFilterRule, handler: Handler<String>) {
        processSecureApi(request = buildUpdateAclRequest(ruleIndex, rule), handler = handler, json = false)
    }

    fun updateMacFilterRuleSync(ruleIndex: Int, rule: MacFilterRule): Triple<Request, Response, Result<String, FuelError>> {
        return processSecureApiSync(request = buildUpdateAclRequest(ruleIndex, rule), json = false)
    }

    fun createMacFilterRule(rule: MacFilterRule, handler: (Request, Response, Result<String, FuelError>) -> Unit) {
        getToken { _, _, result ->
            processSecureApi(
                    request = buildCreateAclRequest(btoken = result.get()[0].device?.token, rule = rule),
                    handler = handler,
                    json = false)
        }
    }

    fun createMacFilterRule(rule: MacFilterRule, handler: Handler<String>) {
        getToken { _, _, result ->
            processSecureApi(
                    request = buildCreateAclRequest(btoken = result.get()[0].device?.token, rule = rule),
                    handler = handler,
                    json = false)
        }
    }

    fun createMacFilterRuleSync(rule: MacFilterRule): Triple<Request, Response, Result<String, FuelError>> {
        val (_, _, result) = getTokenSync()
        return processSecureApiSync(
                request = buildCreateAclRequest(btoken = result.get()[0].device?.token, rule = rule),
                json = false)
    }

    fun createCustomRequest(request: Request, auth: Boolean, handler: (Request, Response, Result<String, FuelError>) -> Unit) {
        if (auth) {
            processSecureApi(request = request, handler = handler, json = false)
        } else {
            request.responseString(handler = handler)
        }
    }

    fun createCustomRequest(request: Request, auth: Boolean, handler: Handler<String>) {
        if (auth) {
            processSecureApi(request = request, handler = handler, json = false)
        } else {
            request.responseString(handler = handler)
        }
    }

    fun createCustomRequestSync(request: Request, auth: Boolean): Triple<Request, Response, Result<String, FuelError>> {
        return if (auth) {
            processSecureApiSync(request = request, json = false)
        } else {
            request.responseString()
        }
    }

    fun logout(handler: (Request, Response, Result<ByteArray, FuelError>) -> Unit) {
        authenticated = false
        buildLogoutRequest().response(handler)
    }

    fun logout(handler: Handler<ByteArray>) {
        authenticated = false
        buildLogoutRequest().response(handler)
    }

    fun logoutSync(): Triple<Request, Response, Result<ByteArray, FuelError>> {
        authenticated = false
        return buildLogoutRequest().response()
    }

    fun startPasswordRecovery(handler: (Request, Response, Result<ByteArray, FuelError>) -> Unit) {
        buildStartRecoveryRequest().response(handler)
    }

    fun startPasswordRecovery(handler: Handler<ByteArray>) {
        buildStartRecoveryRequest().response(handler)
    }

    fun startPasswordRecoverySync(): Triple<Request, Response, Result<ByteArray, FuelError>> {
        return buildStartRecoveryRequest().response()
    }

    fun verifyPasswordRecovery(handler: (Request, Response, Result<List<RecoveryVerify>, Exception>?) -> Unit) {
        buildVerifyRecoveryRequest().responseString { req, res, result ->
            when (result) {
                is Result.Failure -> {
                    handler(req, res, null)
                }
                is Result.Success -> {
                    if (result.get().isEmpty()) {
                        onAuthenticationSuccess(res)
                        handler(req, res, null)
                    } else {
                        val data = Result.of(Gson().fromJson<List<RecoveryVerify>>(result.get()))
                        handler(req, res, data)
                    }
                }
            }
        }
    }

    fun verifyPasswordRecovery(handler: Handler<List<RecoveryVerify>?>) {
        buildVerifyRecoveryRequest().responseString { req, res, result ->
            when (result) {
                is Result.Failure -> {
                    handler.failure(req, res, result.error)
                }
                is Result.Success -> {
                    if (result.get().isEmpty()) {
                        onAuthenticationSuccess(res)
                        handler.success(req, res, null)
                    } else {
                        handler.success(req, res, Gson().fromJson<List<RecoveryVerify>>(result.get()))
                    }
                }
            }
        }
    }

    fun verifyPasswordRecoverySync(): Triple<Request, Response, Result<List<RecoveryVerify>, Exception>?> {
        val (req, res, result) = buildVerifyRecoveryRequest().responseString()
        if (result.component2() != null) {
            return Triple(req, res, null)
        }
        return if (result.get().isEmpty()) {
            onAuthenticationSuccess(res)
            Triple(req, res, null)
        } else {
            Triple(req, res, Result.of(Gson().fromJson<List<RecoveryVerify>>(result.get())))
        }
    }

    fun resetPassword(password: String, handler: (Request, Response, Result<String, FuelError>) -> Unit) {
        getToken { _, _, result ->
            processSecureApi(
                    request = buildResetPasswordRequest(btoken = result.get()[0].device?.token),
                    handler = { req: Request, res: Response, resetResult: Result<String, FuelError> ->
                        if (res.statusCode == 200) {
                            this.password = password
                        }
                        handler(req, res, resetResult)
                    },
                    json = false)
        }
    }

    fun resetPassword(password: String, handler: Handler<String>) {
        getToken { _, _, result ->
            processSecureApi(
                    request = buildResetPasswordRequest(btoken = result.get()[0].device?.token),
                    handler = { req: Request, res: Response, resetResult: Result<String, FuelError> ->
                        if (res.statusCode == 200) {
                            this.password = password
                        }
                        when (resetResult) {
                            is Result.Failure -> {
                                handler.failure(req, res, resetResult.error)
                            }
                            is Result.Success -> {
                                handler.success(req, res, resetResult.get())
                            }
                        }
                    },
                    json = false)
        }
    }

    fun resetPasswordSync(password: String): Triple<Request, Response, Result<String, FuelError>> {
        val (_, _, result) = getTokenSync()
        val resetResult: Triple<Request, Response, Result<String, FuelError>> = processSecureApiSync(
                request = buildResetPasswordRequest(btoken = result.get()[0].device?.token),
                json = false)
        if (resetResult.second.statusCode == 200) {
            this.password = password
        }
        return resetResult
    }

    fun waitForPushButton(maxDuration: Long, pollInterval: Long = 1000): Boolean {
        val (_, response, _) = startPasswordRecoverySync()
        var listenTimer: Timer? = null
        if (response.statusCode == 200) {
            val (_, response, result) = verifyPasswordRecoverySync()
            if (response.statusCode == 200 && result?.get() == null) {
                return true
            }
            var expire: Int = result?.get()?.get(0)?.expires ?: 0
            if (expire > 0) {
                var stop = false
                listenTimer = Timer()
                listenTimer.schedule(delay = maxDuration) {
                    stop = true
                }
                while (expire > 0 && !stop) {
                    val (_, res, verify) = verifyPasswordRecoverySync()
                    if (res.statusCode == 200 && verify?.get() == null) {
                        return true
                    } else {
                        expire = verify?.get()?.get(0)?.expires ?: 0
                    }
                    Thread.sleep(pollInterval)
                }
            }
        }
        listenTimer?.cancel()
        return false
    }

    fun authorize(oauthParam: OauthParam, handler: (Request, Response, Result<CodeResponse, FuelError>) -> Unit) {
        buildOauthAuthorizeRequest(oauthParam).responseObject(gsonDeserializerOf(), handler)
    }

    fun authorize(oauthParam: OauthParam, handler: Handler<CodeResponse>) {
        buildOauthAuthorizeRequest(oauthParam).responseObject(gsonDeserializerOf(), handler)
    }

    fun authorizeSync(oauthParam: OauthParam): Triple<Request, Response, Result<CodeResponse, FuelError>> {
        return buildOauthAuthorizeRequest(oauthParam).responseObject(gsonDeserializerOf())
    }
}