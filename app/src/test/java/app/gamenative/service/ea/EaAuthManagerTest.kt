package app.gamenative.service.ea

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class EaAuthManagerTest {
    @Test
    fun `linked Steam persona before EA persona is not used as game identity`() {
        val personas = JSONArray("""[
            {"namespaceName":"steam","status":"ACTIVE","pidId":100,"personaId":200,"displayName":"linked-steam"},
            {"namespaceName":"cem_ea_id","status":"ACTIVE","pidId":100,"personaId":300,"displayName":"ea-player"}
        ]""")
        assertEquals(Triple("100", "300", "ea-player"), EaAuthManager.selectEaPersona(personas))
    }

    @Test(expected = IllegalStateException::class)
    fun `missing EA persona does not silently use a linked account`() {
        EaAuthManager.selectEaPersona(JSONArray("""[
            {"namespaceName":"steam","status":"ACTIVE","pidId":100,"personaId":200,"displayName":"linked-steam"}
        ]"""))
    }
}
