package com.gdou.ferry.common;

import org.springframework.http.HttpStatus;

/**
 * 身份证号校验（GB 11643 18 位，末位校验位）。
 *
 * 只校验格式与校验位，不做联网实名核验；服务端在下单入口统一调用，前端只提示长度。
 */
public final class IdCards {

    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

    /** 校验位映射：sum % 11 的取值按 1、0、X、9、8、7、6、5、4、3、2 排列。 */
    private static final char[] CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private IdCards() {
    }

    public static boolean valid(String idCard) {
        return idCard != null && checksumOf(idCard) == 0;
    }

    /** 校验身份证号，不合法抛 400 VALIDATION_ERROR。 */
    public static void require(String idCard) {
        if (!valid(idCard)) {
            throw new BizException("身份证号格式不正确", HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR);
        }
    }

    private static int checksumOf(String idCard) {
        if (idCard.length() != 18) {
            return -1;
        }
        String digits = idCard.substring(0, 17);
        for (int i = 0; i < 17; i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (digits.charAt(i) - '0') * WEIGHTS[i];
        }
        return CHECK_CODES[sum % 11] == Character.toUpperCase(idCard.charAt(17)) ? 0 : -1;
    }
}
