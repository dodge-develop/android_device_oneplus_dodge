#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

from extract_utils.fixups_blob import (
    blob_fixup,
    blob_fixups_user_type,
)
from extract_utils.fixups_lib import (
    lib_fixups,
)
from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)

namespace_imports = [
    'hardware/oplus',
    'hardware/qcom-caf/sm8750',
    'vendor/oneplus/sm8750-common',
    'vendor/qcom/opensource/commonsys-intf/display',
]

# Oplus camera config decryption & patching
KEY = bytes.fromhex("6f2170406c247525535e4326412a4d28")
MAGIC = b"\x01\x01"
HEADER_LEN = 4
FOOTER_LEN = 4

def decrypt_oplus_config(blob: bytes) -> bytes:
    """Decrypt an encrypted Oplus camera config blob (must start with 01 01)."""
    ct = blob[HEADER_LEN:len(blob) - FOOTER_LEN]
    pt = AES.new(KEY, AES.MODE_ECB).decrypt(ct)
    pad_len = pt[-1]
    if 1 <= pad_len <= 16:
        pt = pt[:-pad_len]
    return pt

def get_plain_config_bytes(file_path: str) -> bytes:
    """
    Read the config file from disk. If it starts with the encryption magic,
    decrypt it; otherwise return the raw bytes (already plaintext).
    """
    with open(file_path, 'rb') as f:
        raw = f.read()
    if raw[:2] == MAGIC:
        return decrypt_oplus_config(raw)
    return raw

def update_vendor_tag(ctx, file, file_path, vendor_tag, new_value, type_str="Byte", count="1"):
    """
    Generic helper: get plaintext config, find or add a vendor tag, set its Value,
    and write back as plaintext JSON.
    """
    plain_bytes = get_plain_config_bytes(file_path)
    config = json.loads(plain_bytes.decode('utf-8'))

    found = False
    for entry in config:
        if entry.get("VendorTag") == vendor_tag:
            entry["Value"] = new_value
            entry["Type"] = type_str
            entry["Count"] = count
            found = True
            break

    if not found:
        config.append({
            "VendorTag": vendor_tag,
            "Type": type_str,
            "Count": count,
            "Value": new_value
        })

    with open(file_path, 'w') as f:
        json.dump(config, f, indent=2, separators=(',', ': '))

# Disable "Liquid Glass" design
def set_cross_window_blur_zero(ctx, file, file_path, *args, **kwargs):
    update_vendor_tag(ctx, file, file_path, "com.oplus.camera.support.cross.window.blur", "0")

# Unlock 120fps
def set_120fps_guide_support(ctx, file, file_path, *args, **kwargs):
    update_vendor_tag(ctx, file, file_path, "com.oplus.feature.120fps.guide.support", "1")

def set_slowvideo_wide_120fps_not_support(ctx, file, file_path, *args, **kwargs):
    update_vendor_tag(ctx, file, file_path, "com.oplus.feature.slowvideo.wide.120fps.not.support", "1")

def set_video_1080p_120fps_support(ctx, file, file_path, *args, **kwargs):
    update_vendor_tag(ctx, file, file_path, "com.oplus.feature.video.1080p.120fps.support", "1")

def set_video_1080p_120fps_zoom_range(ctx, file, file_path, *args, **kwargs):
    update_vendor_tag(ctx, file, file_path, "com.oplus.feature.video.1080p.120fps.zoom.range", "1,20", "Float", "2")

def set_video_1080p120fps_max_zoom_list(ctx, file, file_path, *args, **kwargs):
    update_vendor_tag(ctx, file, file_path, "com.oplus.feature.video.1080p120fps.max.zoom.list", "4,6,18", "Float", "3")

def set_video_120fps_camera_main_only_support(ctx, file, file_path, *args, **kwargs):
    update_vendor_tag(ctx, file, file_path, "com.oplus.feature.video.120fps.camera.main.only.support", "0")

def set_video_4k_120fps_support(ctx, file, file_path, *args, **kwargs):
    update_vendor_tag(ctx, file, file_path, "com.oplus.feature.video.4k.120fps.support", "1")

def set_video_4k_120fps_zoom_range(ctx, file, file_path, *args, **kwargs):
    update_vendor_tag(ctx, file, file_path, "com.oplus.feature.video.4k.120fps.zoom.range", "1,20", "Float", "2")

def set_video_4k120fps_max_zoom_list(ctx, file, file_path, *args, **kwargs):
    update_vendor_tag(ctx, file, file_path, "com.oplus.feature.video.4k120fps.max.zoom.list", "4,6,18", "Float", "3")

def set_video_dv_120fps_support(ctx, file, file_path, *args, **kwargs):
    update_vendor_tag(ctx, file, file_path, "com.oplus.feature.video.dv.120fps.support", "1")

blob_fixups: blob_fixups_user_type = {
    'odm/etc/init/init.camera_process.rc': blob_fixup()
        .regex_replace('    delete_recursion', '    #delete_recursion'),
    'odm/firmware/fastchg/23821/charging_hyper_mode_config.txt': blob_fixup()
        .regex_replace(r"(PROJECT:=)23893", r"\g<1>23821"),
    'odm/lib64/libAlgoProcess.so': blob_fixup()
        .replace_needed('android.hardware.graphics.common-V5-ndk.so', 'android.hardware.graphics.common-V7-ndk.so'),
    (
        'odm/lib64/libAncHumanSegFigureFusion.so',
        'odm/lib64/libEIS.so',
        'odm/lib64/libEISLive.so',
        'odm/lib64/libHIS.so',
        'odm/lib64/libOPAlgoCamAiBeautyFaceRetouchCn.so',
        'odm/lib64/libOPAlgoCamAiUnifySkin.so',
        'odm/lib64/libOPAlgoCamFaceBeautyCap.so',
    ): blob_fixup()
        .clear_symbol_version('AHardwareBuffer_acquire')
        .clear_symbol_version('AHardwareBuffer_allocate')
        .clear_symbol_version('AHardwareBuffer_describe')
        .clear_symbol_version('AHardwareBuffer_lock')
        .clear_symbol_version('AHardwareBuffer_lockPlanes')
        .clear_symbol_version('AHardwareBuffer_release')
        .clear_symbol_version('AHardwareBuffer_unlock'),
    # Master/Pro-mode photos come out with RED/BLUE swapped. Pro mode captures RAW10 and the
    # OnePlus OCCE tone-mapper (libBasicTonePhoto.so) runs an OpenGL shader whose body contains a
    # U/V (Cb/Cr) reorder `dstYuv = vec4(dstYuv.r, dstYuv.b, dstYuv.g, 1.0)`. On this port the net
    # result is a single uncompensated chroma swap -> R/B swapped JPEG. Undo the swap in the
    # embedded GLSL (length-preserving). Normal/Photo mode does NOT use BasicTone, so this only
    # affects the (otherwise crisp) Master/Pro path..
    'odm/lib64/libBasicTonePhoto.so': blob_fixup()
        .binary_regex_replace(
            b'vec4\\(dstYuv\\.r, dstYuv\\.b, dstYuv\\.g, 1\\.0\\)',
            b'vec4(dstYuv.r, dstYuv.g, dstYuv.b, 1.0)',
        ),
    'odm/lib64/libsensorbridge.so': blob_fixup()
        .replace_needed('android.hardware.sensors-V2-ndk.so', 'android.hardware.sensors-V3-ndk.so'),
    (
        'vendor/lib64/camera/components/com.qti.node.dewarp.so',
        'vendor/lib64/hw/com.qti.chi.override.so',
        'vendor/lib64/libcamximageformatutils.so',
        'vendor/lib64/libchifeature2.so',
    ): blob_fixup()
        .replace_needed('android.hardware.graphics.allocator-V1-ndk.so', 'android.hardware.graphics.allocator-V2-ndk.so'),
    'vendor/lib64/vendor.qti.hardware.camera.offlinecamera-service-impl.so': blob_fixup()
        .replace_needed('android.hardware.graphics.allocator-V1-ndk.so', 'android.hardware.graphics.allocator-V2-ndk.so')
        # convertAndImportBuffer reads the offline-metadata buffer size from the SnapHandle's
        # aligned_width_in_bytes field (handle+0x1c), but on this build that field holds a bogus
        # stride (e.g. 512) for the metadata BLOB while the real byte size is in the next field
        # (aligned_width_in_pixels, handle+0x20). That truncates the metadata copy to 512 bytes
        # and crashes CamX (MetaBuffer::AllocateBuffer). Patch the load to read +0x20 instead of
        # +0x1c:  ldr w27,[x21,#0x1c] (bb1e40b9) -> ldr w27,[x21,#0x20] (bb2240b9).
        # 12-byte anchor = ldr x21,[x12,#0x30]; ldr w27,[x21,#0x1c]; ldr w0,[x21,#0xc].
        .binary_regex_replace(
            b'\x95\x19\x40\xf9\xbb\x1e\x40\xb9\xa0\x0e\x40\xb9',
            b'\x95\x19\x40\xf9\xbb\x22\x40\xb9\xa0\x0e\x40\xb9',
        ),
    (
        'vendor/lib64/libcamxcoreutils.so',
        'vendor/lib64/libcamxods.so',
    ): blob_fixup()
        .replace_needed('libtinyxml2.so', 'libtinyxml2-v34.so'),
    'odm/lib64/libsharebuffer_impl.so': blob_fixup()
        .replace_needed('libutils.so', 'libutils-stock.so')
        .replace_needed('libui.so', 'libui-stock.so'),
    'vendor/lib64/libui-stock.so': blob_fixup()
        .replace_needed('android.hardware.graphics.common-V5-ndk.so', 'android.hardware.graphics.common-V7-ndk.so'),
    'odm/etc/camera/config/oplus_camera_config': blob_fixup()
        .call(set_cross_window_blur_zero)
        .call(set_120fps_guide_support)
        .call(set_slowvideo_wide_120fps_not_support)
        .call(set_video_1080p_120fps_support)
        .call(set_video_1080p_120fps_zoom_range)
        .call(set_video_1080p120fps_max_zoom_list)
        .call(set_video_120fps_camera_main_only_support)
        .call(set_video_4k_120fps_support)
        .call(set_video_4k_120fps_zoom_range)
        .call(set_video_4k120fps_max_zoom_list)
        .call(set_video_dv_120fps_support),
}  # fmt: skip

module = ExtractUtilsModule(
    'dodge',
    'oneplus',
    namespace_imports=namespace_imports,
    blob_fixups=blob_fixups,
    lib_fixups=lib_fixups,
    add_firmware_proprietary_file=True,
)

if __name__ == '__main__':
    utils = ExtractUtils.device_with_common(
        module, 'sm8750-common', module.vendor
    )
    utils.run()
