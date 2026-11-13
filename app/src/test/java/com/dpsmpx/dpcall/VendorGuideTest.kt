package com.dpsmpx.dpcall

import com.dpsmpx.dpcall.util.Vendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VendorGuideTest {
    @Test
    fun detectsVendorsByManufacturerOrBrand() {
        assertEquals(Vendor.XIAOMI, Vendor.detect("Xiaomi", "Redmi"))
        assertEquals(Vendor.XIAOMI, Vendor.detect("Xiaomi", "POCO"))
        assertEquals(Vendor.HONOR, Vendor.detect("HONOR", "HONOR"))
        assertEquals(Vendor.HUAWEI, Vendor.detect("HUAWEI", "HUAWEI"))
        assertEquals(Vendor.OPPO, Vendor.detect("realme", "realme"))
        assertEquals(Vendor.OPPO, Vendor.detect("OnePlus", "OnePlus"))
        assertEquals(Vendor.VIVO, Vendor.detect("vivo", "iQOO"))
        assertEquals(Vendor.SAMSUNG, Vendor.detect("samsung", "samsung"))
        assertNull(Vendor.detect("Google", "google"))
        assertNull(Vendor.detect("", ""))
    }

    @Test
    fun everyVendorHasValidScreens() {
        Vendor.values().forEach { vendor ->
            vendor.screens.forEach { screen ->
                val (pkg, cls) = screen.split('/')
                assert(pkg.isNotBlank() && cls.contains('.')) { "$vendor: $screen" }
            }
        }
    }
}
