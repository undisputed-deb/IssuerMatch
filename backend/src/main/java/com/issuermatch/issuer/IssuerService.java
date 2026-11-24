package com.issuermatch.issuer;

import com.issuermatch.common.NotFoundException;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class IssuerService {

    private final IssuerRepository repo;

    IssuerService(IssuerRepository repo) {
        this.repo = repo;
    }

    Issuer create(IssuerDtos.CreateRequest r) {
        // saveAndFlush so a duplicate name fails here (-> 409), not at commit time
        return repo.saveAndFlush(new Issuer(r.name(), r.sector(), r.region(), r.ownerUserId()));
    }

    @Transactional(readOnly = true)
    Issuer get(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Issuer not found"));
    }

    @Transactional(readOnly = true)
    Page<Issuer> list(Pageable pageable) {
        return repo.findAll(pageable);
    }

    Issuer update(UUID id, IssuerDtos.UpdateRequest r) {
        Issuer issuer = get(id);
        issuer.update(r.name(), r.sector(), r.region());
        return repo.saveAndFlush(issuer);
    }

    void delete(UUID id) {
        repo.delete(get(id));
        repo.flush();
    }
}
