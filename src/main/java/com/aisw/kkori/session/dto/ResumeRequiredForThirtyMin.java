package com.aisw.kkori.session.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 유형별 이력서 필수 검증 — THIRTY_MIN은 {@code resumeId}가 필수다 (FIVE_MIN은 선택).
 *
 * <p>클래스 수준 제약이지만 위반은 {@code resumeId} 필드에 귀속시킨다
 * ({@code addPropertyNode}) — 프론트가 fieldErrors를 resumeId 입력 오류로 처리할 수
 * 있어야 하기 때문. {@code @AssertTrue} 메서드 방식은 가상 프로퍼티명이 노출되어 쓰지 않는다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ResumeRequiredForThirtyMin.Validator.class)
public @interface ResumeRequiredForThirtyMin {

    String message() default "THIRTY_MIN 유형은 resumeId가 필요합니다";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ResumeRequiredForThirtyMin, InterviewSessionCreateRequest> {

        @Override
        public boolean isValid(InterviewSessionCreateRequest request, ConstraintValidatorContext context) {
            // interviewType 누락은 @NotNull이 별도로 보고한다 — 여기서 중복 위반을 만들지 않는다.
            if (request.interviewType() == null
                    || !request.interviewType().requiresResume()
                    || request.resumeId() != null) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("resumeId")
                    .addConstraintViolation();
            return false;
        }
    }
}
