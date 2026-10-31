package app.basera.auth;

/** A login that must not proceed (suspended, wrong domain, ...). The code is shown to the SPA as ?error=. */
public class LoginDeniedException extends RuntimeException {

    private final String code;

    public LoginDeniedException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
