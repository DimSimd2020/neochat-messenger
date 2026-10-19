use std::fmt;

/// Errors surfaced to native clients as `{"code": ..., "message": ...}`.
/// Messages are user-facing Russian text and never contain secrets or plaintext.
#[derive(Debug, Clone)]
pub enum Error {
    /// The server answered with an API error.
    Api { status: u16, code: String, message: String, retry_after_ms: Option<u64>, details: Option<serde_json::Value> },
    /// No connection, timeout, TLS failure.
    Network(String),
    /// Local invariant or crypto failure; the operation was not applied.
    Crypto(String),
    Db(String),
    Invalid(String),
    /// A contact's identity changed; sending is blocked until the user confirms.
    KeyChanged(String),
    NotLoggedIn,
}

pub type Result<T> = std::result::Result<T, Error>;

impl Error {
    pub fn code(&self) -> &str {
        match self {
            Error::Api { code, .. } => code,
            Error::Network(_) => "network",
            Error::Crypto(_) => "crypto",
            Error::Db(_) => "storage",
            Error::Invalid(_) => "invalid",
            Error::KeyChanged(_) => "key_changed",
            Error::NotLoggedIn => "not_logged_in",
        }
    }

    pub fn message(&self) -> String {
        match self {
            Error::Api { message, .. } => message.clone(),
            Error::Network(_) => "Нет сети. Сообщения отправятся после подключения".into(),
            Error::Crypto(m) | Error::Invalid(m) | Error::KeyChanged(m) => m.clone(),
            Error::Db(_) => "Ошибка локального хранилища".into(),
            Error::NotLoggedIn => "Требуется вход".into(),
        }
    }

    pub fn to_json(&self) -> serde_json::Value {
        let mut v = serde_json::json!({"code": self.code(), "message": self.message()});
        if let Error::Api { status, retry_after_ms, .. } = self {
            v["status"] = (*status).into();
            if let Some(ms) = retry_after_ms {
                v["retry_after_ms"] = (*ms).into();
            }
        }
        v
    }

    /// Retry only network failures, 429 and temporary 5xx.
    pub fn retryable(&self) -> bool {
        match self {
            Error::Network(_) => true,
            Error::Api { status, .. } => *status == 429 || *status >= 500,
            _ => false,
        }
    }

    pub fn is_api(&self, c: &str) -> bool {
        matches!(self, Error::Api { code, .. } if code == c)
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Db(d) => write!(f, "{}: {}", self.code(), d),
            _ => write!(f, "{}: {}", self.code(), self.message()),
        }
    }
}

impl From<rusqlite::Error> for Error {
    fn from(e: rusqlite::Error) -> Self {
        Error::Db(e.to_string())
    }
}

impl From<libsignal_protocol::SignalProtocolError> for Error {
    fn from(_: libsignal_protocol::SignalProtocolError) -> Self {
        Error::Crypto("Ошибка шифрования".into())
    }
}

impl From<serde_json::Error> for Error {
    fn from(_: serde_json::Error) -> Self {
        Error::Invalid("Некорректные данные".into())
    }
}

impl From<std::io::Error> for Error {
    fn from(_: std::io::Error) -> Self {
        Error::Invalid("Не удалось прочитать или записать файл".into())
    }
}

/// Maps any displayable library error into a crypto error.
pub fn crypto<E: std::fmt::Display>(_: E) -> Error {
    Error::Crypto("Ошибка шифрования".into())
}
