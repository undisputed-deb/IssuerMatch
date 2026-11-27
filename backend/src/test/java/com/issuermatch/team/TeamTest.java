package com.issuermatch.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.issuermatch.support.Auth;
import com.issuermatch.support.Deals;
import com.issuermatch.support.FakeSupabaseAuthClient;
import com.issuermatch.support.IntegrationTest;
import com.issuermatch.support.Profiles;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Investor firms with several people: invitations, what each role may do, and that firms stay apart. */
@IntegrationTest
class TeamTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired FakeSupabaseAuthClient supabase;

    final JsonMapper json = JsonMapper.builder().build();
    Deals deal;
    final List<UUID> createdOwners = new ArrayList<>();

    @BeforeEach
    void resetFake() {
        supabase.reset();
    }

    @AfterEach
    void cleanUp() {
        if (deal != null) deal.cleanUp(); // cascades members and invites
        for (UUID owner : createdOwners) {
            db.update("DELETE FROM investors WHERE id IN (SELECT investor_id FROM investor_members WHERE user_id = ?)", owner);
        }
    }

    /** A signed-in investor: user id plus the email its token carries. */
    record Person(UUID id, String email) {
        static Person named(String name) {
            return new Person(UUID.randomUUID(), name + "-" + UUID.randomUUID().toString().substring(0, 8) + "@fund.example.test");
        }

        RequestPostProcessor auth() {
            return Auth.investor(id, email);
        }
    }

    static Person director(Deals.Party party) {
        return new Person(party.user(), "md-" + party.user() + "@fund.example.test");
    }

    ResultActions call(MockHttpServletRequestBuilder request, RequestPostProcessor user, String body) throws Exception {
        return mvc.perform(request.with(user).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    ResultActions invite(Person by, String email, String role) throws Exception {
        return call(post("/investors/me/invites"), by.auth(), "{\"email\":\"" + email + "\",\"orgRole\":\"" + role + "\"}");
    }

    /** The token from a fresh invitation. */
    String tokenFor(Person by, String email, String role) throws Exception {
        String path = body(invite(by, email, role).andExpect(status().isCreated())).get("invitePath").asString();
        assertThat(path).matches("/invite/[A-Za-z0-9_-]{43}");
        return path.substring("/invite/".length());
    }

    ResultActions accept(String token, Person who) throws Exception {
        return mvc.perform(post("/investor-invites/" + token + "/accept").with(who.auth()));
    }

    ResultActions preview(String token) throws Exception {
        return mvc.perform(get("/investor-invites/" + token + "/preview").with(Auth.anonymous()));
    }

    Person join(Person md, String role, String name) throws Exception {
        Person p = Person.named(name);
        accept(tokenFor(md, p.email(), role), p).andExpect(status().isOk()).andExpect(jsonPath("$.orgRole").value(role));
        return p;
    }

    ResultActions ask(Person who, String question) throws Exception {
        return call(post("/funding-needs/" + deal.needId + "/questions"), who.auth(), "{\"question\":\"" + question + "\"}");
    }

    ResultActions pass(Person who, Deals.Party firm) throws Exception {
        return call(post("/investor/matches/" + firm.matchResultId() + "/pass"), who.auth(), "{}");
    }

    ResultActions offer(Person who, Deals.Party firm) throws Exception {
        return mvc.perform(post("/commitments").with(who.auth()).header("Idempotency-Key", "deal-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"needId\":\"" + deal.needId + "\",\"investorId\":\""
                        + firm.investorId() + "\",\"amount\":200000,\"investmentRationale\":\"Fits.\"}"));
    }

    ResultActions setRole(Person by, UUID target, String role) throws Exception {
        return call(patch("/investors/me/members/" + target), by.auth(), "{\"orgRole\":\"" + role + "\"}");
    }

    ResultActions remove(Person by, UUID target) throws Exception {
        return mvc.perform(delete("/investors/me/members/" + target).with(by.auth()));
    }

    void assertSharedViews(Person who, Deals.Party firm) throws Exception {
        mvc.perform(get("/investors/me").with(who.auth())).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firm.investorId().toString()));
        mvc.perform(get("/investor/matches").with(who.auth())).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].needId").value(deal.needId.toString()));
        mvc.perform(get("/funding-needs/" + deal.needId).with(who.auth())).andExpect(status().isOk());
        mvc.perform(get("/funding-needs").with(who.auth())).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void anInvitedAnalystJoinsSharesTheDealsAndAsksButCannotPassOrBindTheFirm() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party firm = deal.investor("APPROVED");
        Person md = director(firm);
        Person ana = Person.named("ana");
        String token = tokenFor(md, ana.email().toUpperCase(), "ANALYST");

        preview(token).andExpect(status().isOk()).andExpect(jsonPath("$.firmName").value(startsWith("Deal Fund")))
                .andExpect(jsonPath("$.orgRole").value("ANALYST")).andExpect(jsonPath("$.maskedEmail").value("a***@fund.example.test"))
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.investorId").doesNotExist());
        mvc.perform(get("/investors/me").with(ana.auth())).andExpect(status().isNotFound()); // not a member yet
        accept(token, ana).andExpect(status().isOk()).andExpect(jsonPath("$.investorId").value(firm.investorId().toString()));

        assertSharedViews(ana, firm);
        mvc.perform(get("/investors/me").with(ana.auth())).andExpect(jsonPath("$.myOrgRole").value("ANALYST"));
        ask(ana, "From the analyst").andExpect(status().isCreated());
        mvc.perform(get("/investor/questions").with(md.auth())).andExpect(jsonPath("$[0].question").value("From the analyst"));

        offer(ana, firm).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.success").value(false));
        call(put("/investors/me"), ana.auth(), Profiles.json("Renamed " + UUID.randomUUID())).andExpect(status().isForbidden());
        invite(ana, "x@fund.example.test", "MANAGING_DIRECTOR").andExpect(status().isForbidden());
        setRole(ana, ana.id(), "MANAGING_DIRECTOR").andExpect(status().isForbidden());
        remove(ana, md.id()).andExpect(status().isForbidden());
        mvc.perform(delete("/investors/me/invites/" + UUID.randomUUID()).with(ana.auth())).andExpect(status().isForbidden());
        assertThat(db.queryForObject("SELECT count(*) FROM commitments WHERE need_id = ?", Integer.class, deal.needId)).isZero();

        // the roster is open to every member, the pending invitations only to a managing director
        tokenFor(md, "later@fund.example.test", "VIEWER");
        mvc.perform(get("/investors/me/team").with(ana.auth())).andExpect(status().isOk()).andExpect(jsonPath("$.members", hasSize(2)))
                .andExpect(jsonPath("$.myOrgRole").value("ANALYST")).andExpect(jsonPath("$.pendingInvites", hasSize(0)));
        mvc.perform(get("/investors/me/team").with(md.auth())).andExpect(jsonPath("$.pendingInvites", hasSize(1)))
                .andExpect(jsonPath("$.members[?(@.you==true)].orgRole").value("MANAGING_DIRECTOR"))
                .andExpect(jsonPath("$.members[?(@.you==true)].email").value(md.email()));

        pass(ana, firm).andExpect(status().isForbidden());
        mvc.perform(get("/investor/matches").with(md.auth())).andExpect(jsonPath("$[0].investorDecision").value(org.hamcrest.Matchers.nullValue()));
        pass(md, firm).andExpect(status().isNoContent());
        mvc.perform(get("/investor/matches").with(md.auth())).andExpect(jsonPath("$[0].investorDecision").value("PASSED"));
        // the director still binds the firm, and the whole firm sees the offer
        offer(md, firm).andExpect(status().isCreated());
        mvc.perform(get("/investor/offers").with(ana.auth())).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void aViewerReadsEverythingOfTheFirmAndChangesNothing() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party firm = deal.investor("APPROVED");
        Person md = director(firm);
        offer(md, firm).andExpect(status().isCreated());
        ask(md, "Director asks").andExpect(status().isCreated());
        Person vic = join(md, "VIEWER", "vic");

        assertSharedViews(vic, firm);
        mvc.perform(get("/investor/offers").with(vic.auth())).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/investor/questions").with(vic.auth())).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/funding-needs/" + deal.needId + "/questions").with(vic.auth())).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/investors/me/team").with(vic.auth())).andExpect(status().isOk());

        ask(vic, "Viewer asks").andExpect(status().isForbidden());
        pass(vic, firm).andExpect(status().isForbidden());
        offer(vic, firm).andExpect(status().isForbidden());
        call(put("/investors/me"), vic.auth(), Profiles.json("Viewer Co " + UUID.randomUUID())).andExpect(status().isForbidden());
        invite(vic, "y@fund.example.test", "VIEWER").andExpect(status().isForbidden());
        // a role sent by the client changes nothing: the server reads the database
        call(post("/funding-needs/" + deal.needId + "/questions"), vic.auth(),
                "{\"question\":\"q\",\"orgRole\":\"MANAGING_DIRECTOR\"}").andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT count(*) FROM deal_questions WHERE need_id = ?", Integer.class, deal.needId)).isOne();
    }

    @Test
    void aLinkWorksOnceForTheInvitedAddressAndStopsWhenExpiredOrRevoked() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party firm = deal.investor("APPROVED");
        Person md = director(firm);
        Person invited = Person.named("invited");
        String token = tokenFor(md, invited.email(), "VIEWER");

        accept(token, Person.named("someoneelse")).andExpect(status().isForbidden());
        mvc.perform(post("/investor-invites/" + token + "/accept").with(Auth.investor(invited.id()))) // token without an email
                .andExpect(status().isForbidden());
        mvc.perform(post("/investor-invites/" + token + "/accept").with(Auth.anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(post("/investor-invites/" + token + "/accept").with(Auth.issuer(invited.id()))).andExpect(status().isForbidden());

        accept(token, invited).andExpect(status().isOk());
        accept(token, invited).andExpect(status().isConflict()); // reused
        preview(token).andExpect(status().isNotFound());

        Person late = Person.named("late");
        String expired = tokenFor(md, late.email(), "VIEWER");
        db.update("UPDATE investor_invites SET expires_at = clock_timestamp() - interval '1 second' WHERE token_hash = ?",
                InviteTokens.hash(expired));
        preview(expired).andExpect(status().isNotFound());
        accept(expired, late).andExpect(status().isNotFound());

        String revoked = tokenFor(md, late.email(), "VIEWER");
        UUID id = db.queryForObject("SELECT id FROM investor_invites WHERE token_hash = ?", UUID.class, InviteTokens.hash(revoked));
        mvc.perform(delete("/investors/me/invites/" + id).with(md.auth())).andExpect(status().isNoContent());
        mvc.perform(delete("/investors/me/invites/" + id).with(md.auth())).andExpect(status().isConflict());
        preview(revoked).andExpect(status().isNotFound());
        accept(revoked, late).andExpect(status().isNotFound());

        for (String junk : new String[] {"short", "A".repeat(43), "A".repeat(200), "has.dot".repeat(7)}) {
            preview(junk).andExpect(status().isNotFound());
            accept(junk, late).andExpect(status().isNotFound());
        }
        invite(md, "not-an-address", "VIEWER").andExpect(status().isBadRequest());
        invite(md, "z@fund.example.test", "OWNER").andExpect(status().isBadRequest());
    }

    @Test
    void onlyTheDigestIsStoredAndTheAuditTrailHoldsNeitherTokenNorAddress() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party firm = deal.investor("APPROVED");
        Person md = director(firm);
        Person p = Person.named("quiet");
        String token = tokenFor(md, p.email(), "ANALYST");
        accept(token, p).andExpect(status().isOk());
        setRole(md, p.id(), "VIEWER").andExpect(status().isNoContent());
        remove(md, p.id()).andExpect(status().isNoContent());

        String stored = db.queryForObject("SELECT row_to_json(v)::text FROM investor_invites v WHERE investor_id = ?", String.class,
                firm.investorId());
        assertThat(stored).doesNotContain(token).contains(InviteTokens.hash(token));
        String resource = "investor:" + firm.investorId();
        assertThat(db.queryForList("SELECT action FROM audit_log WHERE resource = ? ORDER BY at", String.class, resource))
                .containsExactly("INVESTOR_INVITE_CREATED", "INVESTOR_INVITE_ACCEPTED", "INVESTOR_MEMBER_ROLE_CHANGED",
                        "INVESTOR_MEMBER_REMOVED");
        assertThat(db.queryForObject("SELECT string_agg(details::text, ' ') FROM audit_log WHERE resource = ?", String.class, resource))
                .doesNotContain(token).doesNotContain(p.email());
        assertThat(db.queryForObject("SELECT count(*) FROM outbox WHERE payload LIKE ? OR payload LIKE ?", Integer.class,
                "%" + token + "%", "%" + p.email() + "%")).isZero();
        // once removed, nothing of the firm is visible any more
        mvc.perform(get("/investor/matches").with(p.auth())).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/investors/me/team").with(p.auth())).andExpect(status().isNotFound());
        mvc.perform(get("/funding-needs/" + deal.needId).with(p.auth())).andExpect(status().isNotFound());
    }

    @Test
    void theLastManagingDirectorStaysAndARemovedOwnerDoesNotComeBack() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party firm = deal.investor("APPROVED"); // inserted directly: its owner is a director without a member row
        Person owner = director(firm);

        setRole(owner, owner.id(), "ANALYST").andExpect(status().isConflict());
        remove(owner, owner.id()).andExpect(status().isConflict());
        setRole(owner, owner.id(), "MANAGING_DIRECTOR").andExpect(status().isNoContent());

        Person second = join(owner, "MANAGING_DIRECTOR", "second");
        // an invitation the owner leaves open must not outlive the owner's rights
        String leftOpen = tokenFor(owner, "alt@fund.example.test", "MANAGING_DIRECTOR");
        remove(second, owner.id()).andExpect(status().isNoContent());
        preview(leftOpen).andExpect(status().isNotFound());

        // investors.owner_user_id still names the removed person, and gives them nothing
        assertThat(db.queryForObject("SELECT owner_user_id FROM investors WHERE id = ?", UUID.class, firm.investorId()))
                .isEqualTo(owner.id());
        mvc.perform(get("/investors/me").with(owner.auth())).andExpect(status().isNotFound());
        mvc.perform(get("/investor/matches").with(owner.auth())).andExpect(jsonPath("$", hasSize(0)));
        offer(owner, firm).andExpect(status().isNotFound());
        setRole(second, second.id(), "VIEWER").andExpect(status().isConflict());
        remove(second, second.id()).andExpect(status().isConflict());

        // they may start a firm of their own
        createdOwners.add(owner.id());
        call(put("/investors/me"), owner.auth(), Profiles.json("Second Start " + UUID.randomUUID())).andExpect(status().isOk())
                .andExpect(jsonPath("$.myOrgRole").value("MANAGING_DIRECTOR"));
        mvc.perform(get("/investors/me").with(owner.auth())).andExpect(jsonPath("$.name").value(startsWith("Second Start")));
    }

    @Test
    void twoDirectorsDemotingEachOtherAtOnceLeaveOne() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party firm = deal.investor("APPROVED");
        Person a = director(firm);
        Person b = join(a, "MANAGING_DIRECTOR", "b");

        for (int round = 0; round < 5; round++) {
            db.update("UPDATE investor_members SET org_role = 'MANAGING_DIRECTOR' WHERE investor_id = ?", firm.investorId());
            var calls = List.of(demoting(a, b), demoting(b, a));
            assertThat(calls.stream().map(CompletableFuture::join).sorted().toList()).containsExactly(204, 403);
            assertThat(db.queryForObject("SELECT count(*) FROM investor_members WHERE investor_id = ?"
                    + " AND org_role = 'MANAGING_DIRECTOR'", Integer.class, firm.investorId())).isOne();
        }
    }

    private CompletableFuture<Integer> demoting(Person by, Person target) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return setRole(by, target.id(), "VIEWER").andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void firmsCannotTouchOrSeeEachOther() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party first = deal.investor("APPROVED");
        Deals.Party second = deal.investor("APPROVED");
        Person md1 = director(first);
        Person md2 = director(second);
        Person analyst1 = join(md1, "ANALYST", "one");
        Person analyst2 = join(md2, "ANALYST", "two");
        ask(analyst1, "first firm secret").andExpect(status().isCreated());
        offer(md1, first).andExpect(status().isCreated());
        String open1 = tokenFor(md1, "pending@fund.example.test", "VIEWER");
        UUID invite1 = db.queryForObject("SELECT id FROM investor_invites WHERE token_hash = ?", UUID.class, InviteTokens.hash(open1));

        mvc.perform(delete("/investors/me/invites/" + invite1).with(md2.auth())).andExpect(status().isNotFound());
        setRole(md2, analyst1.id(), "VIEWER").andExpect(status().isNotFound());
        remove(md2, analyst1.id()).andExpect(status().isNotFound());
        for (Person outsider : new Person[] {md2, analyst2}) {
            mvc.perform(get("/investors/me/team").with(outsider.auth())).andExpect(jsonPath("$.members", hasSize(2)))
                    .andExpect(jsonPath("$.members[?(@.userId=='" + analyst1.id() + "')]", hasSize(0)));
            mvc.perform(get("/investor/offers").with(outsider.auth())).andExpect(jsonPath("$", hasSize(0)));
            mvc.perform(get("/investor/questions").with(outsider.auth())).andExpect(jsonPath("$", hasSize(0)));
            mvc.perform(get("/funding-needs/" + deal.needId + "/questions").with(outsider.auth())).andExpect(jsonPath("$", hasSize(0)));
            pass(outsider, first).andExpect(status().isNotFound());
            offer(outsider, first).andExpect(status().isNotFound());
        }
        mvc.perform(get("/investors/me/team").with(md2.auth())).andExpect(jsonPath("$.pendingInvites", hasSize(0)));
        // someone who already works somewhere cannot join a second firm
        Person poached = new Person(analyst2.id(), analyst2.email());
        accept(tokenFor(md1, poached.email(), "VIEWER"), poached).andExpect(status().isConflict());
        accept(tokenFor(md1, md2.email(), "VIEWER"), md2).andExpect(status().isConflict()); // an owner without a member row too
        mvc.perform(get("/investors/me").with(analyst2.auth())).andExpect(jsonPath("$.id").value(second.investorId().toString()));
    }

    @Test
    void creatingAProfileMakesTheCreatorItsManagingDirector() throws Exception {
        Person p = Person.named("founder");
        createdOwners.add(p.id());
        mvc.perform(get("/investors/me").with(p.auth())).andExpect(status().isNotFound());
        call(put("/investors/me"), p.auth(), Profiles.json("Founded " + UUID.randomUUID())).andExpect(status().isOk())
                .andExpect(jsonPath("$.myOrgRole").value("MANAGING_DIRECTOR"));
        var row = db.queryForMap("SELECT email, display_name, org_role FROM investor_members WHERE user_id = ?", p.id());
        assertThat(row.get("org_role")).isEqualTo("MANAGING_DIRECTOR");
        assertThat(row.get("email")).isEqualTo(p.email());
        assertThat(row.get("display_name")).isEqualTo("Ivy Chen");
        mvc.perform(get("/investors/me").with(p.auth())).andExpect(jsonPath("$.myOrgRole").value("MANAGING_DIRECTOR"));
        // staff views carry no org role
        mvc.perform(get("/investors?size=100&sort=createdAt,desc").with(Auth.admin()))
                .andExpect(jsonPath("$.items[0].myOrgRole").value(nullValue()));
    }

    ResultActions signup(String email, String role, String inviteToken) throws Exception {
        String password = "pw-" + UUID.randomUUID();
        return call(post("/auth/signup"), Auth.anonymous(), "{\"email\":\"" + email + "\",\"password\":\"" + password
                + "\",\"fullName\":\"New Person\",\"role\":\"" + role + "\",\"inviteToken\":\"" + inviteToken + "\"}");
    }

    @Test
    void signingUpThroughALinkNeedsTheInvitedAddressAndAnInvestorAccount() throws Exception {
        deal = new Deals(db, "EQUITY");
        Deals.Party firm = deal.investor("APPROVED");
        String token = tokenFor(director(firm), "new.hire@fund.example.test", "ANALYST");

        signup("intruder@fund.example.test", "INVESTOR", token).andExpect(status().isForbidden());
        signup("new.hire@fund.example.test", "ISSUER", token).andExpect(status().isBadRequest());
        signup("new.hire@fund.example.test", "INVESTOR", "A".repeat(43)).andExpect(status().isNotFound());
        assertThat(supabase.created()).isEmpty(); // no account was left behind by any of those
        signup("New.Hire@fund.example.test", "INVESTOR", token).andExpect(status().isCreated());
        assertThat(supabase.created()).hasSize(1);
    }
}
