#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# AAPT
PRODUCT_AAPT_CONFIG := normal
PRODUCT_AAPT_PREF_CONFIG := xxxhdpi

# Alert slider
PRODUCT_PACKAGES += \
    KeyHandler \
    tri-state-key-calibrate

# Audio
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/audio/audio_policy_volumes.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_volumes.xml \
    $(LOCAL_PATH)/configs/audio/default_volume_tables.xml:$(TARGET_COPY_OUT_VENDOR)/etc/default_volume_tables.xml

# Boot animation
TARGET_SCREEN_HEIGHT := 3168
TARGET_SCREEN_WIDTH := 1440

# Display
$(call soong_config_set,surfaceflinger,frame_rate_category_high,120)
$(call soong_config_set,surfaceflinger,frame_rate_category_min,10)
$(call soong_config_set,surfaceflinger,arr_use_oplus_ltpo_rates,true)

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/display/displayconfig.xml:$(TARGET_COPY_OUT_VENDOR)/etc/displayconfig/display_id_4630946756802996883.xml

# LiveDisplay (PA crash fixed in frameworks/base 7d536ea / local 2892b8bd658f)
$(call soong_config_set_bool,OPLUS_LINEAGE_LIVEDISPLAY_HAL,ENABLE_AF,true)

# Overlays
DEVICE_PACKAGE_OVERLAYS += \
    $(LOCAL_PATH)/overlay-lineage

PRODUCT_PACKAGES += \
    FrameworksResTargetEuicc \
    KeyHandlerResTarget \
    OPlusFrameworksResTarget \
    OPlusSettingsProviderResTarget \
    OPlusSettingsResTarget \
    OPlusSystemUIResTarget

# Sunlight boost (standalone; no device-settings app)
PRODUCT_PACKAGES += \
    SunlightBoost

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/sunlightboost/sunlightboost.rc:$(TARGET_COPY_OUT_SYSTEM)/etc/init/sunlightboost.rc

# NFC
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/nfc/libnfc-mtp-SN220.conf_23821:$(TARGET_COPY_OUT_ODM)/etc/libnfc-mtp-SN220.conf_23821 \
    $(LOCAL_PATH)/configs/nfc/libnfc-mtp-SN220.conf_23893:$(TARGET_COPY_OUT_ODM)/etc/libnfc-mtp-SN220.conf_23893 \
    $(LOCAL_PATH)/configs/nfc/libnfc-nci.conf:$(TARGET_COPY_OUT_VENDOR)/etc/libnfc-nci.conf

# Power
$(call soong_config_set,power_libperfmgr,mode_extension_lib,//hardware/oplus:power-ext-oplus)

# PowerShare
PRODUCT_PACKAGES += \
    vendor.lineage.powershare-service.oplus

# Regional properties
REGIONAL_PROP_FILES := $(wildcard $(LOCAL_PATH)/properties/*/*.prop)

PRODUCT_COPY_FILES += $(foreach f,$(REGIONAL_PROP_FILES), \
    $(f):$(TARGET_COPY_OUT_ODM)/etc/$(patsubst $(LOCAL_PATH)/properties/%,%,$(f)) \
    $(f):$(TARGET_COPY_OUT_RECOVERY)/root/vendor/odm/etc/$(patsubst $(LOCAL_PATH)/properties/%,%,$(f)))

# Soong namespaces
PRODUCT_SOONG_NAMESPACES += \
    $(LOCAL_PATH)

# Telephony
PRODUCT_PACKAGES += \
    OplusEsimSwitcher \
    OplusEuicc

PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.telephony.euicc.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/android.hardware.telephony.euicc.xml

# Touch features
$(call soong_config_set_bool,OPLUS_LINEAGE_TOUCH_HAL,ENABLE_GM,true)
$(call soong_config_set_bool,OPLUS_LINEAGE_TOUCH_HAL,ENABLE_HTPR,true)

# Vibrator
$(call soong_config_set_bool,OPLUS_LINEAGE_VIBRATOR_HAL,USE_EFFECT_STREAM,true)

# Inherit from the common OEM chipset makefile.
$(call inherit-product, device/oneplus/sm8750-common/common.mk)

# Inherit from the proprietary files makefile.
$(call inherit-product, vendor/oneplus/dodge/dodge-vendor.mk)

# Camera
$(call inherit-product-if-exists, vendor/oplus/camera/opluscamera.mk)

# Fusion light sensor (content-immune ALS). Dormant until
# persist.alpha.fusion_light=1 is set on device — see the repo README.
$(call inherit-product-if-exists, vendor/oplus/fusionlight/fusionlight.mk)
