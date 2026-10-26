package com.rivers.cosmo.anchor

import com.rivers.cosmo.kyc.KycStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Sep12Test {
    @Test
    fun listsWhatTheAnchorNeeds() {
        val customer = Sep12.parseCustomer(
            JSONObject(
                """{"status":"NEEDS_INFO",
                   "fields":{
                     "photo_id_front":{"type":"binary","description":"A clear photo of the front of the government issued ID"},
                     "email_address":{"type":"string","description":"Email address","optional":true},
                     "id_type":{"type":"string","description":"Government issued ID","choices":["Passport","Drivers License"]}},
                   "provided_fields":{
                     "first_name":{"type":"string","description":"First name","status":"ACCEPTED"},
                     "last_name":{"type":"string","description":"Last name","status":"REJECTED","error":"Doesn't match the ID"}}}"""
            )
        )
        assertEquals(KycStatus.NEEDS_INFO, customer.status)
        // Required text first, then required files, then optional details.
        val names = customer.needed.map { it.name }
        assertEquals(setOf("id_type", "last_name"), names.take(2).toSet())
        assertEquals(listOf("photo_id_front", "email_address"), names.drop(2))
        assertEquals(listOf("first_name"), customer.provided)
        val lastName = customer.needed.first { it.name == "last_name" }
        assertEquals("Doesn't match the ID", lastName.error)
        assertEquals("Last name", lastName.label)
        assertEquals(listOf("Passport", "Drivers License"), customer.needed.first { it.name == "id_type" }.choices)
        assertTrue(customer.needed.first { it.name == "photo_id_front" }.isFile)
    }

    @Test
    fun readsStatuses() {
        assertEquals(KycStatus.ACCEPTED, Sep12.parseCustomer(JSONObject("""{"id":"d1ce2f48","status":"ACCEPTED"}""")).status)
        assertEquals(KycStatus.PROCESSING, Sep12.parseCustomer(JSONObject("""{"status":"PROCESSING","message":"Checking"}""")).status)
        assertEquals(KycStatus.NEEDS_INFO, KycStatus.parse("SOMETHING_NEW"))
    }
}
