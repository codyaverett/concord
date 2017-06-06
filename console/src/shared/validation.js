// @flow

/**
 * @see com.walmartlabs.concord.common.validation.ConcordKey
 * @type {RegExp}
 */
const CONCORD_KEY_PATTERN = /^[0-9a-zA-Z][0-9a-zA-Z_@.]{2,128}$/;
const COMMIT_ID_PATTERN = /^[0-9a-f]{1,40}$/;

const requiredError = () => "Required";
const tooLongError = (n: any) => `Must be not more than ${n} characters.`;
const invalidRepositoryUrlError = () => "Invalid repository URL: must begin with 'https://' or use 'git@host:path' scheme.";
const invalidCommitIdError = () => "Invalid commit ID: must be a valid revision.";
const concordKeyPatternError = () => "Must start with a digit or a letter, may contain only digits, letters, underscores, '.' or '@' or. Must be between 3 and 128 characters in length.";

export const projectAlreadyExistsError = (n: string) => `Project already exists: ${n}`;

const concordKeyValidator = (v: ?string) => {
    if (!v) {
        return requiredError();
    } else if (!v.match(CONCORD_KEY_PATTERN)) {
        return concordKeyPatternError();
    }
};

const repositoryUrlValidator = (v: ?string) => {
    if (!v) {
        return requiredError();
    }

    if (!v.startsWith("https://") && !v.startsWith("git@")) {
        return invalidRepositoryUrlError();
    }
};

export const project = {
    name: concordKeyValidator,
    description: (v: ?string) => {
        if (v && v.length > 1024) {
            return tooLongError(1024);
        }
    }
};

export const repository = {
    name: concordKeyValidator,
    url: repositoryUrlValidator,
    branch: (v: ?string) => {
        if (v && v.length > 255) {
            return tooLongError(255)
        }
    },
    commitId: (v: ?string) => {
        if (v && !v.match(COMMIT_ID_PATTERN)) {
            return invalidCommitIdError();
        }
    },
    secret: concordKeyValidator
};