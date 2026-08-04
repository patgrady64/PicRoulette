package com.patgrady64.picroulette

data class FileNameParts(
    val stem: String,
    val extension: String
)

data class FileNameValidation(
    val completeFileName: String? = null,
    val errorMessage: String? = null
) {
    val isValid: Boolean
        get() = completeFileName != null && errorMessage == null
}

private val invalidFileNameCharacters =
    Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

fun splitFileName(fileName: String): FileNameParts {
    val trimmed = fileName.trim()
    val lastDot = trimmed.lastIndexOf('.')

    return if (lastDot > 0 && lastDot < trimmed.lastIndex) {
        FileNameParts(
            stem = trimmed.substring(0, lastDot),
            extension = trimmed.substring(lastDot)
        )
    } else {
        FileNameParts(
            stem = trimmed,
            extension = ""
        )
    }
}

fun validateRenamedFileName(
    originalFileName: String,
    requestedStem: String
): FileNameValidation {
    val stem = requestedStem.trim()
    val originalParts = splitFileName(originalFileName)

    if (stem.isBlank()) {
        return FileNameValidation(
            errorMessage = "Enter a filename."
        )
    }

    if (stem == "." || stem == "..") {
        return FileNameValidation(
            errorMessage = "That filename is not allowed."
        )
    }

    if (invalidFileNameCharacters.containsMatchIn(stem)) {
        return FileNameValidation(
            errorMessage = "Do not use \\ / : * ? \" < > or | in the filename."
        )
    }

    if (stem.endsWith('.') || stem.endsWith(' ')) {
        return FileNameValidation(
            errorMessage = "The filename cannot end with a period or space."
        )
    }

    val completeName = stem + originalParts.extension

    if (completeName.length > 255) {
        return FileNameValidation(
            errorMessage = "The filename is too long."
        )
    }

    if (completeName == originalFileName) {
        return FileNameValidation(
            errorMessage = "Enter a different filename."
        )
    }

    return FileNameValidation(
        completeFileName = completeName
    )
}
