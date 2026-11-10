pub trait Authenticator: Send + Sync {
    fn accept_device_confirmation(&self, cb: Box<dyn FnOnce(bool) + Send>);
    fn get_device_code(&self, previous_was_incorrect: bool, cb: Box<dyn FnOnce(String) + Send>);
    fn get_email_code(
        &self,
        email: String,
        previous_was_incorrect: bool,
        cb: Box<dyn FnOnce(String) + Send>,
    );
}
