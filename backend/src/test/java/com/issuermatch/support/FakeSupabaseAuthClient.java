package com.issuermatch.support;

import com.issuermatch.signup.SupabaseAuthAdminClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** In-memory stand-in for the Supabase Auth Admin API. Remembers what would have been created. */
public class FakeSupabaseAuthClient implements SupabaseAuthAdminClient {

    private final List<NewUser> created = new ArrayList<>();
    private final Set<String> emails = new HashSet<>();
    private RuntimeException failure;

    @Override
    public synchronized UUID createUser(NewUser user) {
        if (failure != null) throw failure;
        if (!emails.add(user.email().toLowerCase())) throw new EmailAlreadyRegistered();
        created.add(user);
        return UUID.randomUUID();
    }

    public synchronized List<NewUser> created() {
        return List.copyOf(created);
    }

    public synchronized void reset() {
        created.clear();
        emails.clear();
        failure = null;
    }

    public synchronized void failWith(RuntimeException e) {
        failure = e;
    }
}
