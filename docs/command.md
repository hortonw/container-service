# Command
If you want XNAT to execute your docker image, you will need a Command. The Command is a collection of properties that describe your docker image, and which XNAT can read to understand what your image is and how to run it:

* Which docker image is it? What is its ID?
* Does it have a human-friendly name we can use for it?
* How do you run it? What does the command-line string look like?
* Does it need files? Where should they go? How do you want to get those out of XNAT?
* Does it produce any files at the end? Those have to get back into XNAT, so where should they go?

## Get your Command into XNAT
XNAT can read the Command only if it is structured as a JSON object. There are a few ways to get your Command into XNAT:

### UI
We hope to soon have a web interface for defining / submitting Commands.

### POST  JSON to `/xapi/commands`
If your docker image already exists on the docker server that XNAT can talk to, you can submit the JSON document to the container-service REST API by POSTing the JSON to `{XNAT}/xapi/commands`.

### Make your docker image "XNAT-ready"
If you are trying to use a docker image that isn't yours, you will need to define your Command separately from the docker image. But if you control the docker image, you can "bake" one or more Command definitions directly into the image by including it/them as an image label. If you do that, then XNAT can read the Command definitions directly off the image when you first pull it down from Docker Hub or any time later via the REST API.

See the [section on labels in the Dockerfile reference documentation](https://docs.docker.com/engine/reference/builder/#/label) for more information on labeling your images. In short, you can include a label on your image with the key `"org.nrg.commands"`, and with the value being a string representation of a JSON list of Commands. The line in your Dockerfile should look something like this:

        LABEL "org.nrg.commands"="[{command1}, {command2}, ...]"

Each one of those `{commandN}` things above is a full command definition. The label value should be a JSON list (i.e. it should have square brackets `[ ]`) even if you only have one Command definition inside.

## Example command

    {
        "name": "",
        "description": "",
        "info-url": "",
        "docker-image": "",
        "run": {
            "command-line": "",
            "mounts": [
                {
                    "name": "",
                    "type": "",
                    "path": "",
                    "file-input": "",
                    "resource": ""
                }
            ],
            "environment-variables": {
                "envName1": "envVal1",
                "envName2": "#inputReplacementKey#"
            },
            "ports": {
                "80": "8080",
                "22": "52222"
            }
        },
        "inputs": [
            {
                "name": "",
                "description": "",
                "type": "",
                "required": true,
                "prerequisites": "",
                "parent": "",
                "parent-property": "",
                "matcher": "",
                "default-value": "",
                "replacement-key": "",
                "command-line-flag": "",
                "command-line-separator": "",
                "true-value": "",
                "false-value": ""
            }
        ],
        "outputs": [
            {
                "name": "",
                "description": "",
                "type": "",
                "label": "",
                "parent": "",
                "files": {
                    "mount": "",
                    "path": ""
                }
            }
        ]
    }

## JSON parameters

- **name** - The name of the command. The combination of the "name" and "docker-image" values must be unique.
- **description** - A human-friendly description of the command.
- **info-url** - A URL where more info on the command, or the image, or both, can be found.
- **docker-image** - An identifier of the image this command describes. Can be in "repo/image:tag" format or "sha256:123abc..." hash format. If the command JSON is embedded in the labels of a docker image, then this field may be omitted.
- **run** - Fields that will be used to instruct docker how to run a container from the image.
    - **command-line** - This string is a templatized version of the command-line string that will be executed inside the container. The templatized portions will be resolved at launch time with the values of the command's inputs. See the section on [template strings](#template-strings) below for more detail.
    - **mounts** - A list of mount points that will be created for your container.
        - **name** - The name of the mount. You can use this to refer to the mount elsewhere in the command, e.g. when creating an output.
        - **type** - Either "input" or "output". Input mounts can have files pre-staged from XNAT, and are created read-only. Output mounts have no pre-staged files, but are writable.
        - **path** - The absolute path inside your container at which the mount will be created.
        - **file-input** - The **name** of an input which will be used as the source for the files. If the input has **type** "Resource", the **resource** property below can be omitted and the files in the Resource will be provided to the mount. Otherwise the input must have a **type** that can contain Resources—Project, Subject, Session, Scan, or Assessor—and the value of the **resource** property must be the label of one of the input's resources.
        - **resource** - The label of a resource under the above-named **file-input**, which will provide the files for an input mount.
    - **environment-variables** - Key/value pairs of environment variables to set in the container. Both keys and values can be templates that will be filled by input values at runtime.
    - **ports** - String key/value pairs of ports to expose. The key is the port inside the container, the value is the port to expose out on the host. In other words, entries in this map should be of the form `"container_port": "host_port"`. Keys and values can be templates.
- **inputs** - A list of inputs that will be used to resolve the command and launch the container.
    - **name** - The name of the input. You can use this to refer to the input elsewhere in the command.
    - **description** - A human-friendly description of the input.
    - **type** - One of string, boolean, number, file, Project, Subject, Session, Scan, Assessor, Resource, or Config. See the section on [input types](#input-types) below for more. Default: string.
    - **required** - A boolean value (true/false) whether this input is required. If a required input does not have a value at runtime, an error is thrown. Default: false.
    - **prerequisites** - A comma-separated string containing the names of other inputs that must be resolved (i.e. receive runtime values) before this input can be resolved. Note that an input's parent is automatically considered a prerequisite to that input, and need not be included in the list of prerequisites.
    - **parent** - The name of another input, which is the "parent" of this input. See the section on [parent inputs](#parent-inputs) for more.
    - **parent-property** - The name of a property of the parent. If this input's parent input is one of the XNAT input types, use `parent-property` to automatically set this input's value to that property of the parent. For instance, if the parent is a `Session`, this input could have `parent-property=label` and its value would be set at runtime to the session's label.
    - **matcher** - A [JSONPath query string](#jsonpath-query-strings) used to determine if an input value is valid or not. For instance, if the parent input is a `Session`, and this input is a `Scan`, we can make sure that this input only matches scans with a DICOM resource by setting the matcher to `"DICOM" in @.resources[*].label`, or only matches scans of a certain type by setting the matcher to `@.scan-type == "MPRAGE"`.
    - **default-value** - A value that will be used if no other value is provided at runtime.
    - **replacement-key** - A shorthand way to refer to this input's value elsewhere in the command. Default: the input's name bracketed by "#"; e.g. for an input named "foo" the default replacement-key is "#foo#".
    - **command-line-flag** - When this input's value is replaced in the command-line string, it is preceded by this flag. E.g. an input with value "foo" and command-line-flag "--flag" will appear in the command-line string as "--flag foo".
    - **command-line-separator** - The character separating the command-line-flag from the value in the command-line. Default: " ".
    - **true-value** - The string to use in the command line for a boolean input when its value is `true`. Some examples: "true", "T", "Y", "1", "--a-flag". Default: "true".
    - **false-value** - The string to use in the command line for a boolean input when its value is `false`. Some examples: "false", "F", "N", "0", "--some-other-flag". Default: "false".
- **outputs** - A list of outputs that will be used to upload files produced by the container.
    - **name** - The name of the output.
    - **description** - A human-friendly description of the output.
    - **type** - One of "Assessor" or "Resource". Assessor outputs should point to a properly-formatted XML document that holds the details of the assessor object to be created. Resource outputs should point to a file or directory that will be uploaded to a new resource.
    - **label** - The label of the resource that will be created. Required for outputs of Resource type; ignored for outputs of Assessor type.
    - **parent** - The name of an input, of an XNAT type, under which this output object will be created.
    - **files** - Where the file(s) can be found inside the container.
        - **mount** - The name of a mount, which must be defined in this command and must have type "output", into which your container wrote whatever file(s) you intend to upload.
        - **path** - The relative path within a mount at which output files can be found. Value can be templatized with input replacement keys.

# Template Strings
When you define a Command, you can leave many of the values as "templates". These templates are placeholder strings, also known as "replacement keys", which tell the container service "When you launch a container from this Command, you will have values for your inputs; I want you to use one of those values here."

Lots of properties in the Command can use template strings:

* run.command-line - See a simple example in the [Hello world example](#hello-world-example), but also see the caveats in the [complex example](#more-complex-command-line-example) below.
* run.environment-variables - Both the environment variable name and value can be templates.
* run.ports - Both the container port and host port can be templates.
* output.files.path - The relative path within a mount at which output files can be found.

# JSONPath Strings
More info to come.

## JSONPath query strings
More info to come.

# Input Types
string, boolean, number, file, Project, Subject, Session, Scan, Assessor, Resource, Config

# Parent Inputs
More info to come.

# Examples
## Hello world example
Let's go over a simple use case. You have a string input, and you want whatever value gets put into that input to be printed to the screen. We could define a command like this:

    {
        "name": "hello-world",
        "description": "Prints a string to stdout",
        "docker-image": "busybox/latest",
        "inputs": [
            {
                "name": "my_cool_input",
                "description": "The string that will be printed",
                "type": "string",
                "default-value": "Hello world"
            }
        ],
        "run": {
            "command-line": "echo #my_cool_input#"
        }
    }

In this example, you have one input, which is a string, and is named `"my_cool_input"`. The value of `run.command-line` is what will be executed inside the container when it is launched. However, before container service launches that container, it will look at that `run.command-line` value and see that it recognizes the string `#my_cool_input#` as a replacement key for the input with name `my_cool_input`. Each input has a replacement key, which is by default just the input's name surrounded by `#`es. But you can customize an input's replacement key by setting the `replacement-key` property on the input.

When the container-service sees a replacement key, it will replace it with the input's value. In this case, that means `#my_cool_input#` will be replaced by `Hello world`. (Well, technically, that is only partially correct. See the [more complex example](#more-complex-command-line-example) below.)

## More complex command-line example
What I said above is that when the container service looks at one of the strings in the list above and sees an input's replacement key, it fills in the input's value. That is correct, most of the time. under certain circumstances, tweaks are made to the input's value before it is inserted in place of the replacement key.

1. Inputs with type `boolean` will have their value (`true` or `false`) mapped to a string. By default these strings are `"true"` and `"false"`, but you can customize these values by setting `input.true-value` and `input.false-value` respectively. Some example true/false string pairs could be `1/0`, `Y/N`, `True/False`, etc. Or, if you choose, you could set `input.true-value` to some flag and set `input.false-value` to a blank string, or vice versa. In that way, you could set a flag on the command line only if a certain input is `true` but do not set any flag if the input is `false`; for example, you could have the input named `"recursive"` have `false-value=""` and `true-value="-r"`.
2. When the input's value is replaced into the command-line, you can optionally prefix the value with a flag by setting `input.command-line-flag`. So if my input `foo` has value `bar` and `command-line-flag="--my-input-value"`, the flag and value will be smashed together and will replace the replacement key as `--my-input-value bar`. If you want the flag and value to be joined by something other than a space, for instance an equals sign, you can set that as `input.command-line-separator`.

Let's see an example Command with these properties set.

    {
        "name": "complex-example",
        "description": "An example Command with more complex inputs",
        "docker-image": "busybox/latest",
        "inputs": [
            {
                "name": "the_boolean",
                "description": "A boolean input",
                "type": "boolean",
                "default-value": false,
                "replacement-key": "[THE_BOOLEAN]",
                "true-value": "T",
                "false-value": "F",
                "command-line-flag": "--bool",
                "command-line-separator": "="
            },
            {
                "name": "the_string",
                "description": "A string input",
                "type": "string",
                "replacement-key": "a-string",
                "command-line-flag": "--str",
            }
        ],
        "run": {
            "command-line": "/run/my_script.sh [THE_BOOLEAN] a-string",
            "environment-variables": {
                "STR_VAL": "a-string",
                "BOOL_VAL": "[THE_BOOLEAN]"
            }
        }
    }

Let's say this Command is used to launch a container, and let's say that no input values are passed in (i.e. only the defaults will be used). Then `the_boolean` will have value `false` and `the_string` will have no value. The command line would become `"/run/my_script.sh --bool=F"`. The environment variables would be `STR_VAL=` and `BOOL_VAL=F`.

Now let's say we launch a container from the Command again, and this time we pass in input values `the_boolean=true` and `the_string=Hey`. Then the command line would become `"/run/my_script.sh --bool=T --str Hey"`
