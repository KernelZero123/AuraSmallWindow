# Merge these entries into YOUR device/product configuration.
# No Aura-specific hardware, brightness or refresh-rate configuration is included.
PRODUCT_SYSTEM_PROPERTIES += \
    ro.aura.small_window=true \
    ro.aura.small_window_scale=74

PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.software.freeform_window_management.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/android.software.freeform_window_management.xml

# Merge property_contexts.fragment into your existing policy directory.
# Merge overlay-values.xml into a framework-res overlay for your device.
