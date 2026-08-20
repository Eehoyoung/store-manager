package com.storemanager.api.persona;

import com.storemanager.api.persona.PersonaDtos.PersonaRequest;
import com.storemanager.api.persona.PersonaDtos.PersonaResponse;
import com.storemanager.api.persona.PersonaDtos.PreviewRequest;
import com.storemanager.api.persona.PersonaDtos.PreviewResponse;
import com.storemanager.api.persona.PersonaDtos.StyleSampleListResponse;
import com.storemanager.api.persona.PersonaDtos.StyleSampleRequest;
import com.storemanager.api.persona.PersonaDtos.StyleSampleResponse;
import com.storemanager.api.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** docs/13 §7 페르소나 API (Sprint 5 P1~P4). */
@RestController
public class PersonaController {

    private final PersonaService personaService;

    public PersonaController(PersonaService personaService) {
        this.personaService = personaService;
    }

    @GetMapping("/api/v1/stores/{storeId}/persona")
    public PersonaResponse get(@PathVariable UUID storeId) {
        return personaService.getPersona(CurrentUser.publicId(), storeId);
    }

    @PutMapping("/api/v1/stores/{storeId}/persona")
    public PersonaResponse update(@PathVariable UUID storeId, @Valid @RequestBody PersonaRequest req) {
        return personaService.updatePersona(CurrentUser.publicId(), storeId, req);
    }

    @PostMapping("/api/v1/stores/{storeId}/persona/preview")
    public PreviewResponse preview(@PathVariable UUID storeId, @Valid @RequestBody PreviewRequest req) {
        return personaService.preview(CurrentUser.publicId(), storeId, req);
    }

    @GetMapping("/api/v1/stores/{storeId}/persona/style-samples")
    public StyleSampleListResponse styleSamples(@PathVariable UUID storeId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return personaService.listStyleSamples(CurrentUser.publicId(), storeId, page, size);
    }

    @PostMapping("/api/v1/stores/{storeId}/persona/style-samples")
    @ResponseStatus(HttpStatus.CREATED)
    public StyleSampleResponse addStyleSample(@PathVariable UUID storeId,
            @Valid @RequestBody StyleSampleRequest req) {
        return personaService.addStyleSample(CurrentUser.publicId(), storeId, req);
    }

    @DeleteMapping("/api/v1/stores/{storeId}/persona/style-samples/{sampleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStyleSample(@PathVariable UUID storeId, @PathVariable Long sampleId) {
        personaService.deleteStyleSample(CurrentUser.publicId(), storeId, sampleId);
    }
}
