package com.evcs.favorites.data.routing

/**
 * Repository and content provider for the Google Maps Platform API Key creation guide,
 * official Google Cloud Console deep-links, free-tier quotas, and troubleshooting matrix.
 */
object ApiKeyGuideProvider {

    const val PACKAGE_NAME: String = GoogleRoutesClient.ANDROID_PACKAGE_VALUE

    const val URL_CONSOLE_HOME: String = "https://console.cloud.google.com"
    const val URL_BILLING: String = "https://console.cloud.google.com/billing"
    const val URL_ROUTES_API_LIBRARY: String = "https://console.cloud.google.com/apis/library/routes.googleapis.com"
    const val URL_CREDENTIALS: String = "https://console.cloud.google.com/apis/credentials"
    const val URL_QUOTAS: String = "https://console.cloud.google.com/iam-admin/quotas"

    /**
     * Returns the complete 5-step walkthrough for personal BYOK Google Routes API key creation.
     */
    fun getGuideSteps(): List<ApiKeyGuideStep> = listOf(
        ApiKeyGuideStep(
            stepNumber = 1,
            title = "Tạo dự án & Thiết lập thanh toán",
            subtitle = "Google Cloud Console",
            instructions = """
                1. Truy cập **Google Cloud Console** và đăng nhập bằng tài khoản Google cá nhân của bạn.
                2. Tạo một dự án mới (ví dụ đặt tên dự án là **TramsacEV**).
                3. Liên kết một tài khoản thanh toán (Billing Account) với dự án.
                *Lưu ý:* Google bắt buộc liên kết thanh toán để kích hoạt API, nhưng bạn được tặng **10.000 lượt yêu cầu miễn phí mỗi tháng** cho Routes API Essentials.
                4. Thiết lập cảnh báo ngân sách (Budget Alert) ở mức **${'$'}0 USD** để nhận email thông báo nếu có bất kỳ chi phí phát sinh nào, đảm bảo hoàn toàn không mất phí ngoài ý muốn.
            """.trimIndent(),
            tips = "Google sẽ không tính phí nếu bạn sử dụng dưới 10.000 lượt gọi/tháng. Đặt Budget Alert $0 giúp bạn tuyệt đối yên tâm.",
            actionUrl = URL_BILLING,
            actionLabel = "Mở Google Cloud Billing",
            copyableValue = "TramsacEV"
        ),
        ApiKeyGuideStep(
            stepNumber = 2,
            title = "Kích hoạt Routes API",
            subtitle = "APIs & Services > Library",
            instructions = """
                1. Trong Google Cloud Console, vào menu điều hướng chọn **APIs & Services** > **Library** (Thư viện API).
                2. Tìm kiếm từ khóa **"Routes API"** (hoặc nhấp trực tiếp vào liên kết bên dưới).
                3. Chọn **Routes API** và nhấn nút **Enable** (Bật) để kích hoạt dịch vụ cho dự án của bạn.
            """.trimIndent(),
            tips = "Hãy chắc chắn chọn đúng 'Routes API' (chứ không phải 'Directions API' cũ) để ứng dụng sử dụng công nghệ tính toán ma trận tuyến đường v2 tối ưu pin.",
            actionUrl = URL_ROUTES_API_LIBRARY,
            actionLabel = "Bật Routes API",
            copyableValue = "Routes API"
        ),
        ApiKeyGuideStep(
            stepNumber = 3,
            title = "Tạo khóa API (API Key)",
            subtitle = "APIs & Services > Credentials",
            instructions = """
                1. Đi tới mục **APIs & Services** > **Credentials** (Thông tin xác thực).
                2. Nhấp vào nút **+ Create Credentials** (+ Tạo thông tin xác thực) ở thanh công cụ phía trên và chọn **API key**.
                3. Một hộp thoại sẽ hiển thị khóa API vừa tạo (có tiền tố bắt đầu bằng `AIzaSy...`).
                4. Nhấp biểu tượng sao chép để lưu chuỗi khóa API này lại trước khi tiến hành cấu hình bảo mật ở Bước 4.
            """.trimIndent(),
            tips = "Không chia sẻ khóa API này cho người khác. Hãy hoàn thành Bước 4 để giới hạn phạm vi sử dụng của khóa.",
            actionUrl = URL_CREDENTIALS,
            actionLabel = "Mở trang Credentials",
            copyableValue = URL_CREDENTIALS
        ),
        ApiKeyGuideStep(
            stepNumber = 4,
            title = "Cấu hình giới hạn khóa (Quan trọng cho BYOK)",
            subtitle = "Bảo mật khóa API cá nhân",
            instructions = """
                1. Trong danh sách Credentials, nhấp vào tên khóa API vừa tạo (hoặc biểu tượng chỉnh sửa).
                2. **API Restrictions (Giới hạn API):**
                   - Chọn tùy chọn **"Restrict key"** (Hạn chế khóa).
                   - Trong danh sách các API, tích chọn DUY NHẤT **"Routes API"**, sau đó nhấn OK. Điều này ngăn chặn kẻ xấu sử dụng khóa cho bất kỳ dịch vụ tính phí nào khác.
                3. **Application Restrictions (Giới hạn ứng dụng):**
                   - Chọn **"None" (Không giới hạn)** đối với khóa cá nhân sử dụng trong ứng dụng di động.
                   - *Giải thích quan trọng:* Yêu cầu HTTP REST API trực tiếp từ thiết bị người dùng không gửi kèm chữ ký chứng chỉ SHA-1 tùy biến; nếu bạn chọn 'Android apps', Google Cloud sẽ yêu cầu vân tay chứng chỉ SHA-1 và trả về lỗi HTTP 403 Forbidden. Khóa của bạn đã an toàn tuyệt đối nhờ việc giới hạn riêng cho 'Routes API' và cài đặt Budget Alert $0 ở Bước 1.
                4. Nhấn **Save** (Lưu) để hoàn tất cấu hình bảo mật.
            """.trimIndent(),
            tips = "Để tránh lỗi HTTP 403: Đặt Application Restrictions là 'None' và chỉ bật 'Routes API' trong API Restrictions. Package Name ứng dụng là com.evcs.favorites.",
            actionUrl = URL_CREDENTIALS,
            actionLabel = "Cấu hình hạn chế khóa",
            copyableValue = PACKAGE_NAME
        ),
        ApiKeyGuideStep(
            stepNumber = 5,
            title = "Kiểm tra & Lưu vào TramsacEV",
            subtitle = "Cài đặt ứng dụng TramsacEV",
            instructions = """
                1. Quay lại ứng dụng TramsacEV, dán chuỗi khóa API vừa tạo vào ô **Khóa API Google Maps**.
                2. Nhấn nút **"Kiểm tra kết nối"** để ứng dụng gửi một yêu cầu thử nghiệm siêu nhỏ đến Google Cloud và xác thực tính hợp lệ.
                3. Khi xuất hiện thông báo kiểm tra thành công, nhấn **"Lưu cài đặt"**.
                4. Bạn đã sẵn sàng tận hưởng tính năng định tuyến giao thông trực tiếp đa tầng với dữ liệu Google Maps chính xác nhất!
            """.trimIndent(),
            tips = "Nếu gặp lỗi trong quá trình kiểm tra, xem ngay mục 'Câu hỏi thường gặp & Khắc phục lỗi' bên dưới để nhận hướng dẫn chi tiết theo mã lỗi.",
            actionUrl = null,
            actionLabel = "Kiểm tra kết nối",
            copyableValue = null
        )
    )

    /**
     * Returns structured troubleshooting items covering all primary Google API failure modes.
     */
    fun getTroubleshootingItems(): List<ApiKeyTroubleshootingItem> = listOf(
        ApiKeyTroubleshootingItem(
            errorCode = ApiKeyErrorCode.INVALID_KEY,
            title = "Khóa API không hợp lệ (HTTP 400)",
            cause = "Chuỗi khóa API bị nhập sai ký tự, có khoảng trắng thừa, hoặc chưa được tạo đúng trên Google Cloud Console.",
            solution = "Sao chép lại chính xác chuỗi khóa (bắt đầu bằng 'AIza...') từ trang Credentials và đảm bảo không có ký tự khoảng trắng thừa ở đầu/cuối.",
            remediationUrl = URL_CREDENTIALS,
            relatedStepNumber = 3
        ),
        ApiKeyTroubleshootingItem(
            errorCode = ApiKeyErrorCode.BILLING_DISABLED,
            title = "Chưa kích hoạt thanh toán (HTTP 403 Billing)",
            cause = "Dự án Google Cloud chưa được liên kết với Tài khoản thanh toán (Billing Account).",
            solution = "Truy cập Google Cloud Billing, tạo hoặc liên kết tài khoản thanh toán với dự án 'TramsacEV'. Bạn vẫn được miễn phí 10.000 lượt yêu cầu mỗi tháng.",
            remediationUrl = URL_BILLING,
            relatedStepNumber = 1
        ),
        ApiKeyTroubleshootingItem(
            errorCode = ApiKeyErrorCode.API_NOT_ENABLED,
            title = "Chưa kích hoạt Routes API (HTTP 403 Service Disabled)",
            cause = "Dự án Google Cloud chưa bật dịch vụ 'Routes API' trong thư viện API.",
            solution = "Truy cập Google Cloud API Library, tìm 'Routes API' và nhấn nút 'Enable' (Bật).",
            remediationUrl = URL_ROUTES_API_LIBRARY,
            relatedStepNumber = 2
        ),
        ApiKeyTroubleshootingItem(
            errorCode = ApiKeyErrorCode.RESTRICTION_ERROR,
            title = "Khóa bị giới hạn ứng dụng hoặc IP (HTTP 403 Restriction)",
            cause = "Khóa API đang được cài đặt Application Restrictions là 'Android apps' hoặc 'IP addresses', khiến yêu cầu REST từ ứng dụng bị từ chối.",
            solution = "Vào chỉnh sửa khóa trong mục Credentials: chuyển Application Restrictions thành 'None' (Không giới hạn) và đảm bảo API Restrictions đã chọn 'Routes API'.",
            remediationUrl = URL_CREDENTIALS,
            relatedStepNumber = 4
        ),
        ApiKeyTroubleshootingItem(
            errorCode = ApiKeyErrorCode.QUOTA_EXCEEDED,
            title = "Vượt quá hạn ngạch yêu cầu (HTTP 429 Quota Exceeded)",
            cause = "Số lượt yêu cầu trong ngày hoặc trong tháng đã vượt quá giới hạn hạn ngạch hoặc hạn mức ngân sách được thiết lập.",
            solution = "Kiểm tra mức sử dụng hạn ngạch trên Google Cloud Console Quotas hoặc tăng hạn ngạch nếu có nhu cầu sử dụng cao hơn.",
            remediationUrl = URL_QUOTAS,
            relatedStepNumber = 1
        ),
        ApiKeyTroubleshootingItem(
            errorCode = ApiKeyErrorCode.NETWORK_ERROR,
            title = "Lỗi kết nối mạng hoặc máy chủ",
            cause = "Không thể kết nối tới máy chủ Google Routes API do sự cố kết nối mạng, mất internet, hoặc thời gian chờ (timeout).",
            solution = "Kiểm tra lại kết nối Wi-Fi hoặc 4G/5G của thiết bị và thử lại nút 'Kiểm tra kết nối'.",
            remediationUrl = null,
            relatedStepNumber = 5
        )
    )

    /**
     * Resolves an actionable troubleshooting item by matching error messages from [RoutingPreferencesManager].
     *
     * @param errorMessage The error message or exception description.
     * @return Corresponding [ApiKeyTroubleshootingItem], or null if the message is blank.
     */
    fun getTroubleshootingForError(errorMessage: String): ApiKeyTroubleshootingItem? {
        if (errorMessage.isBlank()) return null
        val items = getTroubleshootingItems().associateBy { it.errorCode }

        return when {
            errorMessage.contains("Khóa API Google không hợp lệ", ignoreCase = true) ||
            errorMessage.contains("INVALID_KEY", ignoreCase = true) ||
            errorMessage.contains("API key not valid", ignoreCase = true) ->
                items[ApiKeyErrorCode.INVALID_KEY]

            errorMessage.contains("Dự án Google Cloud chưa kích hoạt thanh toán", ignoreCase = true) ||
            errorMessage.contains("billing", ignoreCase = true) ->
                items[ApiKeyErrorCode.BILLING_DISABLED]

            errorMessage.contains("Chưa kích hoạt 'Routes API'", ignoreCase = true) ||
            (errorMessage.contains("Routes API", ignoreCase = true) && errorMessage.contains("chưa kích hoạt", ignoreCase = true)) ||
            errorMessage.contains("service_disabled", ignoreCase = true) ||
            errorMessage.contains("has not been used", ignoreCase = true) ->
                items[ApiKeyErrorCode.API_NOT_ENABLED]

            errorMessage.contains("Khóa API bị giới hạn ứng dụng hoặc IP", ignoreCase = true) ||
            errorMessage.contains("giới hạn ứng dụng", ignoreCase = true) ||
            errorMessage.contains("restriction", ignoreCase = true) ||
            errorMessage.contains("blocked", ignoreCase = true) ->
                items[ApiKeyErrorCode.RESTRICTION_ERROR]

            errorMessage.contains("Vượt quá hạn ngạch yêu cầu", ignoreCase = true) ||
            errorMessage.contains("hạn ngạch", ignoreCase = true) ||
            errorMessage.contains("quota", ignoreCase = true) ||
            errorMessage.contains("429") ->
                items[ApiKeyErrorCode.QUOTA_EXCEEDED]

            else ->
                items[ApiKeyErrorCode.NETWORK_ERROR]
        }
    }

    /**
     * Returns Google Maps Platform free tier usage thresholds, $0 cost guarantee details, and safety practices.
     */
    fun getFreeTierInfo(): FreeTierInfo {
        return FreeTierInfo(
            monthlyFreeRequests = 10_000,
            skuName = "Routes API Essentials",
            quotaSummary = "Miễn phí 10.000 lượt tính toán ma trận tuyến đường mỗi tháng theo biểu phí Routes API Essentials của Google Maps Platform.",
            costGuaranteeDescription = "Đảm bảo $0 chi phí bằng cách cài đặt Cảnh báo ngân sách (Budget Alert) ở mức $0 USD trong Google Cloud Console. Bạn sẽ nhận email cảnh báo ngay khi có chi phí phát sinh mà không lo bị trừ tiền bất ngờ.",
            safetyRecommendations = listOf(
                "Chỉ kích hoạt API Restrictions cho duy nhất 'Routes API' để bảo vệ khóa.",
                "Đặt Application Restrictions là 'None' đối với khóa cá nhân BYOK trên ứng dụng di động để tránh lỗi 403.",
                "Tạo Budget Alert mức $0 USD tại Google Cloud Billing để luôn yên tâm $0 chi phí.",
                "Không chia sẻ chuỗi khóa API công khai trên mạng xã hội hoặc kho lưu trữ mở."
            )
        )
    }
}
