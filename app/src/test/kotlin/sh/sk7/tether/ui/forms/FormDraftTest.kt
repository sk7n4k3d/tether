package sh.sk7.tether.ui.forms

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import sh.sk7.tether.data.api.FormAnswerValue
import sh.sk7.tether.data.api.FormFieldDto

/**
 * **La construction d'une reponse de formulaire, regle par regle.**
 *
 * ⚠️ Chaque regle encodee ici a ete **mesuree sur le serveur 2.0.x le 2026-09-26**, et non
 * deduite du schema — cinq bugs sur cinq sont venus d'un schema lu au lieu d'une reponse reelle.
 * Les mesures sont citees dans le commentaire de la regle concernee ; le test, lui, fige le
 * comportement cote app pour qu'il ne puisse plus regresser en silence.
 */
class FormDraftTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun field(raw: String): FormFieldDto = json.decodeFromString(FormFieldDto.serializer(), raw)

    private fun fields(vararg raws: String): List<FormFieldDto> = raws.map(::field)

    private fun ready(submission: FormSubmission): Map<String, FormAnswerValue> {
        assertIs<FormSubmission.Ready>(submission, "attendu Ready, recu $submission")
        return submission.answer
    }

    private fun invalid(submission: FormSubmission): List<FieldError> {
        assertIs<FormSubmission.Invalid>(submission, "attendu Invalid, recu $submission")
        return submission.errors
    }

    // ------------------------------------------------------------------
    // Valeurs par defaut : le serveur ne les applique PAS, l'app doit pre-remplir
    // ------------------------------------------------------------------

    /**
     * ⚠️ Mesure : `POST reply {"answer":{}}` sur un champ `{"type":"integer","default":7}` rend
     * `204` avec `answer:{}` — le serveur **n'ecrit pas le defaut**. C'est donc au brouillon de le
     * porter, sinon la valeur est perdue.
     */
    @Test
    fun `le brouillon porte les defauts de chaque type`() {
        val f = fields(
            """{"key":"s","type":"string","default":"abc"}""",
            """{"key":"n","type":"number","default":1.5}""",
            """{"key":"i","type":"integer","default":3}""",
            """{"key":"b","type":"boolean","default":true}""",
            """{"key":"m","type":"multiselect","options":[{"value":"x","label":"X"}],"default":["x"]}""",
            """{"key":"libre","type":"string"}""",
        )

        val draft = FormDraft.of(f)

        assertEquals(FormDraftValue.Raw("abc"), draft["s"])
        assertEquals(FormDraftValue.Raw("1.5"), draft["n"])
        assertEquals(FormDraftValue.Raw("3"), draft["i"])
        assertEquals(FormDraftValue.Toggle(true), draft["b"])
        assertEquals(FormDraftValue.Choice(setOf("x")), draft["m"])
        assertEquals(null, draft["libre"], "un champ sans defaut n'a pas d'entree")
    }

    /** Un defaut explicite `null` n'est pas un defaut : c'est une absence. */
    @Test
    fun `un defaut null ne remplit pas le brouillon`() {
        val f = fields("""{"key":"s","type":"string","default":null}""")
        assertEquals(null, FormDraft.of(f)["s"])
    }

    // ------------------------------------------------------------------
    // Le type est celui de la classe, jamais une chaine devinee
    // ------------------------------------------------------------------

    /**
     * ⚠️ Mesure : envoyer `"3"` (chaine) a un `integer` rend
     * `400 Expected number for form field`. La classe [FormAnswerValue.Integer] rend ce cas
     * impossible : un entier part en entier.
     */
    @Test
    fun `integer part en entier et non en chaine`() {
        val f = fields("""{"key":"n","type":"integer","required":true}""")
        val answer = ready(FormAnswerBuilder.build(f, FormDraft().text("n", "3")))

        val value = answer["n"]
        assertIs<FormAnswerValue.Integer>(value)
        assertEquals(3L, value.value)
        assertEquals("3", value.toJson().toString(), "un entier ne doit pas porter de partie decimale")
    }

    /** Un décimal part en décimal. */
    @Test
    fun `number part en decimal`() {
        val f = fields("""{"key":"n","type":"number","required":true}""")
        val answer = ready(FormAnswerBuilder.build(f, FormDraft().text("n", "3.5")))
        assertEquals(FormAnswerValue.Decimal(3.5), answer["n"])
    }

    /** `"3.0"` est accepte pour un entier (mesure : le serveur accepte `3.0`), mais part en `3`. */
    @Test
    fun `integer accepte 3 point 0 et normalise en entier`() {
        val f = fields("""{"key":"n","type":"integer","required":true}""")
        val answer = ready(FormAnswerBuilder.build(f, FormDraft().text("n", "3.0")))
        assertEquals("3", answer["n"]!!.toJson().toString())
    }

    /** `3.5` n'est pas un entier : refus local avec une raison. */
    @Test
    fun `integer refuse une partie decimale`() {
        val f = fields("""{"key":"n","type":"integer","required":true}""")
        val errors = invalid(FormAnswerBuilder.build(f, FormDraft().text("n", "3.5")))
        assertEquals(1, errors.size)
        assertEquals("n", errors.first().key)
    }

    // ------------------------------------------------------------------
    // external : toujours acquitte, meme hidden, meme when non rempli
    // ------------------------------------------------------------------

    /**
     * ⚠️ Mesure : omettre un `external` rend `400 External form field must be acknowledged`, et
     * c'est vrai meme quand sa condition `when` n'est pas remplie (le serveur verifie
     * l'acquittement **avant** la porte `when`). Envoyer `true` rend `204`.
     *
     * ⚠️ MAIS l'acquittement est un **consentement**, pas une valeur a fabriquer : l'app
     * ne l'envoie que si l'utilisateur a coche « Reçu » (la case existe dans l'ecran).
     * La fabriquait toujours, c'etait fabriquer une confirmation jamais donnee.
     */
    @Test
    fun `external non coche bloque l envoi, coche il part a true`() {
        val f = fields(
            """{"key":"mode","type":"string","options":[{"value":"a","label":"A"},{"value":"b","label":"B"}]}""",
            """{"key":"site","type":"external","required":true,"url":"https://example.com","hidden":true,"when":[{"key":"mode","op":"eq","value":"b"}]}""",
        )
        // Sans la case : Invalid, avec l'erreur sur le champ external.
        val bloque = FormAnswerBuilder.build(f, FormDraft().text("mode", "a"))
        assertIs<FormSubmission.Invalid>(bloque)
        assertTrue(bloque.errors.any { it.key == "site" })

        // Avec la case : Flag(true), meme cache et condition non remplie (le serveur
        // verifie l'acquittement avant la porte `when` — mesure).
        val answer = ready(FormAnswerBuilder.build(f, FormDraft().text("mode", "a").toggle("site", true)))
        assertEquals(FormAnswerValue.Flag(true), answer["site"])
    }

    // ------------------------------------------------------------------
    // when : condition non remplie -> champ absent
    // ------------------------------------------------------------------

    /**
     * ⚠️ Mesure : envoyer un champ dont le `when` est faux rend `400 Form field is not active`.
     * Envoyer l'inverse — l'omettre — rend `204`. On retire donc le champ, defaut compris.
     */
    @Test
    fun `un champ when inactif n est pas envoye, defaut compris`() {
        val f = fields(
            """{"key":"mode","type":"string","options":[{"value":"a","label":"A"},{"value":"b","label":"B"}]}""",
            """{"key":"detail","type":"string","default":"dv","when":[{"key":"mode","op":"eq","value":"b"}]}""",
        )
        val answer = ready(FormAnswerBuilder.build(f, FormDraft().text("mode", "a")))
        assertFalse(answer.containsKey("detail"))
    }

    /** Le meme champ, condition remplie : il est envoye. */
    @Test
    fun `un champ when actif est envoye`() {
        val f = fields(
            """{"key":"mode","type":"string","options":[{"value":"a","label":"A"},{"value":"b","label":"B"}]}""",
            """{"key":"detail","type":"string","when":[{"key":"mode","op":"eq","value":"b"}]}""",
        )
        val answer = ready(FormAnswerBuilder.build(f, FormDraft().text("mode", "b").text("detail", "v")))
        assertEquals(FormAnswerValue.Text("v"), answer["detail"])
    }

    /**
     * ⚠️ Mesure : un champ reference **absent** du brouillon rend `eq` et `neq` tous deux faux
     * (`m` omis, `d when m neq b`, `d` envoye -> `400 Form field is not active`). Un `neq` n'est
     * donc **pas** « vrai par defaut ».
     */
    @Test
    fun `neq sur un champ absent rend le champ inactif`() {
        val f = fields(
            """{"key":"mode","type":"string","options":[{"value":"a","label":"A"},{"value":"b","label":"B"}]}""",
            """{"key":"detail","type":"string","when":[{"key":"mode","op":"neq","value":"b"}]}""",
        )
        val answer = ready(FormAnswerBuilder.build(f, FormDraft().text("detail", "v")))
        assertFalse(answer.containsKey("detail"))
    }

    /** Le defaut compte pour evaluer la condition : `mode` defaut `a` rend `when eq a` vrai. */
    @Test
    fun `le defaut d un champ satisfait la condition d un autre`() {
        val f = fields(
            """{"key":"mode","type":"string","options":[{"value":"a","label":"A"},{"value":"b","label":"B"}],"default":"a"}""",
            """{"key":"detail","type":"string","default":"dv","when":[{"key":"mode","op":"eq","value":"a"}]}""",
        )
        val answer = ready(FormAnswerBuilder.build(f, FormDraft.of(f)))
        assertEquals(FormAnswerValue.Text("dv"), answer["detail"])
    }

    /** Une condition `multiselect` est vraie si la valeur est **dans** la selection (mesure). */
    @Test
    fun `when multiselect utilise l appartenance`() {
        val f = fields(
            """{"key":"m","type":"multiselect","options":[{"value":"a","label":"A"},{"value":"b","label":"B"}]}""",
            """{"key":"d","type":"string","when":[{"key":"m","op":"eq","value":"a"}]}""",
        )
        val inside = ready(FormAnswerBuilder.build(f, FormDraft().select("m", "a", true).text("d", "v")))
        assertEquals(FormAnswerValue.Text("v"), inside["d"])

        val outside = ready(FormAnswerBuilder.build(f, FormDraft().select("m", "b", true).text("d", "v")))
        assertFalse(outside.containsKey("d"))
    }

    /** Une condition booleenne compare le booleen, pas sa forme textuelle. */
    @Test
    fun `when booleen compare la valeur logique`() {
        val f = fields(
            """{"key":"b","type":"boolean"}""",
            """{"key":"d","type":"string","when":[{"key":"b","op":"eq","value":true}]}""",
        )
        val yes = ready(FormAnswerBuilder.build(f, FormDraft().toggle("b", true).text("d", "v")))
        assertEquals(FormAnswerValue.Text("v"), yes["d"])

        val no = ready(FormAnswerBuilder.build(f, FormDraft().toggle("b", false).text("d", "v")))
        assertFalse(no.containsKey("d"))
    }

    /** Deux conditions `when` se cumulent (ET logique, mesure). */
    @Test
    fun `deux conditions when doivent toutes deux etre vraies`() {
        val f = fields(
            """{"key":"m1","type":"string","options":[{"value":"a","label":"A"},{"value":"x","label":"X"}]}""",
            """{"key":"m2","type":"string","options":[{"value":"b","label":"B"},{"value":"x","label":"X"}]}""",
            """{"key":"d","type":"string","when":[{"key":"m1","op":"eq","value":"a"},{"key":"m2","op":"eq","value":"b"}]}""",
        )
        val half = ready(FormAnswerBuilder.build(f, FormDraft().text("m1", "a").text("m2", "x").text("d", "v")))
        assertFalse(half.containsKey("d"))

        val full = ready(FormAnswerBuilder.build(f, FormDraft().text("m1", "a").text("m2", "b").text("d", "v")))
        assertEquals(FormAnswerValue.Text("v"), full["d"])
    }

    // ------------------------------------------------------------------
    // requis, non-requis : ce qui part et ce qui ne part pas
    // ------------------------------------------------------------------

    /**
     * ⚠️ Mesure : `hidden:true, required:true` **sans `when`** et omis rend
     * `400 Missing required form field` — le serveur ne traite donc pas `hidden` comme inactif.
     * L'app doit l'exiger comme un champ visible.
     */
    @Test
    fun `un champ hidden sans when reste exige quand required`() {
        val f = fields("""{"key":"h","type":"string","hidden":true,"required":true}""")
        val errors = invalid(FormAnswerBuilder.build(f, FormDraft()))
        assertEquals("h", errors.first().key)
    }

    /** Un optionnel vide n'est pas une erreur : il n'est simplement pas envoye. */
    @Test
    fun `un champ optionnel vide n est pas envoye`() {
        val f = fields("""{"key":"s","type":"string"}""")
        val answer = ready(FormAnswerBuilder.build(f, FormDraft()))
        assertFalse(answer.containsKey("s"))
    }

    /**
     * ⚠️ Mesure : une chaine vide pour un `string` requis rend
     * `400 Missing required form field`. On le refuse localement, avec la meme raison.
     */
    @Test
    fun `une chaine vide ne satisfait pas un requis`() {
        val f = fields("""{"key":"s","type":"string","required":true}""")
        val errors = invalid(FormAnswerBuilder.build(f, FormDraft().text("s", "")))
        assertEquals("s", errors.first().key)
    }

    /** `false` et `0` sont de **vraies** valeurs : ils satisfont un requis (mesure : 204). */
    @Test
    fun `false et zero satisfont un requis`() {
        val f = fields("""{"key":"b","type":"boolean","required":true}""", """{"key":"n","type":"number","required":true}""")
        val answer = ready(FormAnswerBuilder.build(f, FormDraft().toggle("b", false).text("n", "0")))
        assertEquals(FormAnswerValue.Flag(false), answer["b"])
        assertEquals(FormAnswerValue.Decimal(0.0), answer["n"])
    }

    /** Un multiselect requis et vide est refuse ; le meme avec une selection passe. */
    @Test
    fun `multiselect requis refuse une selection vide`() {
        val f = fields("""{"key":"m","type":"multiselect","options":[{"value":"a","label":"A"}],"required":true}""")
        val errors = invalid(FormAnswerBuilder.build(f, FormDraft()))
        assertEquals("m", errors.first().key)

        val ok = ready(FormAnswerBuilder.build(f, FormDraft().select("m", "a", true)))
        assertEquals(FormAnswerValue.Items(listOf("a")), ok["m"])
    }

    // ------------------------------------------------------------------
    // contraintes locales : motif, longueur, bornes, options
    // ------------------------------------------------------------------

    /**
     * ⚠️ Mesure decisive : le motif du serveur fait une correspondance **partielle**, pas un
     * alignement complet. `pattern:"[0-9]+"` accepte `abc123` et refuse `abc`. Utiliser
     * `Regex.matches` cote app refuserait `abc123` que le serveur accepte — bloquer une reponse
     * valide serait pire que de ne pas valider.
     */
    @Test
    fun `le motif est cherche partiellement, pas aligne sur toute la chaine`() {
        val f = fields("""{"key":"s","type":"string","pattern":"[0-9]+"}""")

        assertTrue(FormAnswerBuilder.build(f, FormDraft().text("s", "abc123")) is FormSubmission.Ready)
        assertTrue(FormAnswerBuilder.build(f, FormDraft().text("s", "abc")) is FormSubmission.Invalid)
    }

    @Test
    fun `longueur et bornes sont refusees localement`() {
        val tooShort = fields("""{"key":"s","type":"string","minLength":5}""")
        assertTrue(FormAnswerBuilder.build(tooShort, FormDraft().text("s", "ab")) is FormSubmission.Invalid)

        val tooLong = fields("""{"key":"s","type":"string","maxLength":2}""")
        assertTrue(FormAnswerBuilder.build(tooLong, FormDraft().text("s", "abcd")) is FormSubmission.Invalid)

        val tooBig = fields("""{"key":"n","type":"number","maximum":5}""")
        assertTrue(FormAnswerBuilder.build(tooBig, FormDraft().text("n", "9")) is FormSubmission.Invalid)

        val tooSmall = fields("""{"key":"n","type":"integer","minimum":5}""")
        assertTrue(FormAnswerBuilder.build(tooSmall, FormDraft().text("n", "2")) is FormSubmission.Invalid)
    }

    /**
     * ⚠️ Une borne `"Infinity"` (chaine) n'est **pas** une erreur : le schema `Form.NumberField`
     * l'autorise explicitement. La traiter comme invalide ferait echouer un formulaire sain.
     */
    @Test
    fun `une borne Infinity en chaine n est pas une contrainte`() {
        val f = fields("""{"key":"n","type":"number","maximum":"Infinity"}""")
        assertEquals(null, f.first().maxBound())
        assertEquals(null, f.first().minBound())
    }

    @Test
    fun `une valeur hors options est refusee sauf si custom`() {
        val strict = fields("""{"key":"s","type":"string","options":[{"value":"a","label":"A"}]}""")
        assertTrue(FormAnswerBuilder.build(strict, FormDraft().text("s", "z")) is FormSubmission.Invalid)

        val free = fields("""{"key":"s","type":"string","options":[{"value":"a","label":"A"}],"custom":true}""")
        assertTrue(FormAnswerBuilder.build(free, FormDraft().text("s", "z")) is FormSubmission.Ready)
    }

    @Test
    fun `minItems et maxItems d un multiselect sont verifies`() {
        val f = fields(
            """{"key":"m","type":"multiselect","options":[{"value":"a","label":"A"},{"value":"b","label":"B"},{"value":"c","label":"C"}],"minItems":2,"maxItems":2}""",
        )
        val one = FormAnswerBuilder.build(f, FormDraft().select("m", "a", true))
        assertTrue(one is FormSubmission.Invalid)

        val two = FormAnswerBuilder.build(f, FormDraft().select("m", "a", true).select("m", "b", true))
        assertTrue(two is FormSubmission.Ready)

        val three = FormAnswerBuilder
            .build(f, FormDraft().select("m", "a", true).select("m", "b", true).select("m", "c", true))
        assertTrue(three is FormSubmission.Invalid)
    }

    /** Le JSON produit est celui attendu par le serveur, cles et types compris. */
    @Test
    fun `le JSON de la reponse porte les types exacts`() {
        val f = fields(
            """{"key":"s","type":"string","required":true}""",
            """{"key":"i","type":"integer"}""",
            """{"key":"b","type":"boolean"}""",
            """{"key":"m","type":"multiselect","options":[{"value":"x","label":"X"}]}""",
            """{"key":"site","type":"external","url":"https://e.com"}""",
        )
        val draft = FormDraft()
            .text("s", "v")
            .text("i", "2")
            .toggle("b", true)
            .select("m", "x", true)
            // L'external est un consentement : il est coché dans l'ecran, pas fabrique.
            .toggle("site", true)

        val submission = FormAnswerBuilder.build(f, draft)
        assertIs<FormSubmission.Ready>(submission)
        val jsonObject = submission.toJsonObject()

        assertEquals(JsonPrimitive("v"), jsonObject["s"])
        assertEquals(JsonPrimitive(2L), jsonObject["i"])
        assertEquals(JsonPrimitive(true), jsonObject["b"])
        assertEquals(JsonArray(listOf(JsonPrimitive("x"))), jsonObject["m"])
        assertEquals(JsonPrimitive(true), jsonObject["site"])
    }

    /**
     * ⚠️ Mesure : une cle inconnue dans `answer` rend `400 Unknown form field`. On ne construit la
     * reponse **qu'a partir des champs declares** : c'est structurellement impossible d'en ajouter.
     */
    @Test
    fun `la reponse ne contient que des cles declarees`() {
        val f = fields("""{"key":"a","type":"string","required":true}""")
        val answer = ready(FormAnswerBuilder.build(f, FormDraft().text("a", "x")))
        assertEquals(setOf("a"), answer.keys)
    }

    /** Un type de champ inconnu est ignore, jamais envoye : un type qu'on ne comprend pas ne se
     *  devine pas. */
    @Test
    fun `un type de champ inconnu n est pas envoye`() {
        val f = fields("""{"key":"weird","type":"spectrum"}""")
        val answer = ready(FormAnswerBuilder.build(f, FormDraft().text("weird", "x")))
        assertFalse(answer.containsKey("weird"))
    }
}
